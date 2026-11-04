package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;

/**
 * 岗位服务（《API接口设计》5.1–5.5、5.8）：CRUD + `analyzed` / `stale` 判定 + 求职进度切换。
 *
 * 与简历模块同构，差异只在两处：
 * 1. 岗位是**手输文本**（Bean Validation 校验），无文件与文本提取；
 * 2. **支持更新**——描述可修改，因此存在"分析已过期"（stale）：
 *    当前 description 的 SHA-256 与最新成功分析的 source_hash 不一致即为过期
 *    （判定单点在 {@link JobAnalysisService#isStale}，三处出口共用）。
 *
 * 归属校验（{@link #getOwnedJob}）一律带 user_id、查不到返回 404（不区分"不存在"与"他人"）；
 * 逻辑删除手写 status 条件（《数据库设计》5.4，不用 @TableLogic）。
 *
 * 写路径不加 @Transactional（与 M3/M4 一致：单条 INSERT/UPDATE 本身原子）。
 * 本类**不注入** {@link JobAnalysisService}——后者依赖本类的 getOwnedJob 取实体，
 * 注入会成环；stale 判定以静态纯函数形式复用。
 */
@Service
@ConditionalOnProperty(prefix = "app.job", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalJobService implements JobService {

    private final JobMapper jobMapper;

    private final JobAnalysisMapper jobAnalysisMapper;

    public LocalJobService(JobMapper jobMapper, JobAnalysisMapper jobAnalysisMapper) {
        this.jobMapper = jobMapper;
        this.jobAnalysisMapper = jobAnalysisMapper;
    }

    /** 创建（5.1） */
    public JobDetail create(Long userId, JobSaveRequest request) {
        Job job = new Job();
        job.setUserId(userId);
        job.setCompanyName(normalizeCompanyName(request.getCompanyName()));
        job.setJobName(request.getJobName().trim());
        // description 原样入库：**不 trim 不做任何 normalize**——它是 source_hash 的计算基准，
        // stale 判定建立在"所见即所存"之上（《数据库设计》5.2），请勿在此加工
        job.setDescription(request.getDescription());
        // 投递链接是**普通可空文本**（不是 hash 基准），与公司名同样归一：空串 → null，其余 trim
        job.setApplyUrl(normalizeApplyUrl(request.getApplyUrl()));
        // 新岗位的求职进度一律从「待投递」开始（M21）；库列也有 DEFAULT，此处显式赋值使装配不依赖默认值
        job.setProgressStatus(Job.PROGRESS_PENDING);
        job.setStatus(Job.STATUS_ACTIVE);
        // 显式设置时间：MP 的 insert 不会回读数据库默认值（同 ResumeService.upload）；
        // 裁到秒与 DATETIME(0) 对齐（MySQL 对低精度目标按舍入处理，不裁会出现跨接口 1 秒抖动）
        LocalDateTime now = LocalDateTime.now().withNano(0);
        job.setCreatedAt(now);
        job.setUpdatedAt(now);
        jobMapper.insert(job);
        // 新 id 不可能已有分析（AUTO_INCREMENT 不复用 id + FK RESTRICT 阻止父行被删）
        return toDetail(job, false, false);
    }

    /** 列表（5.2）：仅本人有效岗位，created_at 倒序（id 倒序作稳定排序的 tie-breaker） */
    public PageResult<JobView> list(Long userId, PageQuery query) {
        Page<Job> page = new Page<>(query.getPage(), query.getSize());
        IPage<Job> result = jobMapper.selectPage(page, Wrappers.<Job>lambdaQuery()
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE)
                .orderByDesc(Job::getCreatedAt)
                .orderByDesc(Job::getId));

        Map<Long, JobAnalysis> latestByJobId = latestSuccessByJobIds(result.getRecords());
        List<JobView> records = result.getRecords().stream()
                .map(job -> toView(job, latestByJobId.get(job.getId())))
                .toList();
        return PageResult.of(result, records);
    }

    /** 详情（5.3）：含完整 description（编辑弹窗回填用） */
    public JobDetail detail(Long userId, Long id) {
        Job job = getOwnedJob(userId, id);
        JobAnalysis latest = findLatestSuccess(job.getId());
        return toDetail(job, latest != null, JobAnalysisService.isStale(job.getDescription(), latest));
    }

    /**
     * 更新（5.4）：**全量更新**——jobName 与 description 必须提交（校验同创建），
     * companyName 省略或传 null 均表示清空。
     *
     * 显式 `.set(updatedAt)` 的原因：MySQL 的 `ON UPDATE CURRENT_TIMESTAMP` 在**行值未变化时
     * 不刷新**——用户原样再保存一次（幂等提交）时，若靠 DB 自动刷新或更新后重查，
     * 拿到的会是旧值，违反 §5.4「updatedAt 已刷新」的契约。
     * 响应采用**内存装配**（同 {@code UserService.updateProfile}），不回查。
     *
     * 本接口**不修改** progressStatus（M21）：编辑弹窗只编辑描述类字段，状态由
     * {@link #updateProgressStatus} 单独维护，重填表单不会把进度打回「待投递」。
     */
    public JobDetail update(Long userId, Long id, JobSaveRequest request) {
        Job job = getOwnedJob(userId, id);
        // 写入 UPDATE 的最终值：响应装配与 stale 判定必须使用同一变量，不得再加工
        String companyName = normalizeCompanyName(request.getCompanyName());
        String jobName = request.getJobName().trim();
        String description = request.getDescription();
        String applyUrl = normalizeApplyUrl(request.getApplyUrl());
        LocalDateTime now = LocalDateTime.now().withNano(0);

        jobMapper.update(null, Wrappers.<Job>lambdaUpdate()
                .eq(Job::getId, id)
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE)
                .set(Job::getUpdatedAt, now)
                .set(Job::getCompanyName, companyName)
                .set(Job::getJobName, jobName)
                .set(Job::getDescription, description)
                .set(Job::getApplyUrl, applyUrl));

        job.setCompanyName(companyName);
        job.setJobName(jobName);
        job.setDescription(description);
        job.setApplyUrl(applyUrl);
        job.setUpdatedAt(now);

        // 描述变化后，旧分析的 source_hash 与之不再一致 → stale=true（§5.4）
        JobAnalysis latest = findLatestSuccess(id);
        return toDetail(job, latest != null, JobAnalysisService.isStale(description, latest));
    }

    /**
     * 求职进度切换（5.8，M21）：只改 progressStatus 与 updatedAt，不动描述与其他字段。
     *
     * 单独成接口（而非复用全量 PUT）的原因：全量更新语义要求提交全部字段，
     * 列表行内切换若走 PUT 就得先取详情再整份回传，既多余又容易覆盖掉并发编辑；
     * 与 M17 面试日程的状态流转（`PUT /interview-schedules/{id}/status`）同一模式。
     *
     * **不设状态机**：求职进度可任意切换/回退（用户可能录错或环节回退），
     * 只做枚举白名单校验；服务层兜底 `@Pattern`，防绕过 DTO 校验的调用路径。
     * updated_at 显式写入并裁到秒（同 {@link #update}，不依赖 ON UPDATE 的刷新时机）。
     */
    public JobDetail updateProgressStatus(Long userId, Long id, String progressStatus) {
        Job job = getOwnedJob(userId, id);
        if (!Job.isValidProgressStatus(progressStatus)) {
            throw BusinessException.badRequest("状态不合法");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jobMapper.update(null, Wrappers.<Job>lambdaUpdate()
                .eq(Job::getId, id)
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE)
                .set(Job::getProgressStatus, progressStatus)
                .set(Job::getUpdatedAt, now));

        job.setProgressStatus(progressStatus);
        job.setUpdatedAt(now);
        JobAnalysis latest = findLatestSuccess(id);
        return toDetail(job, latest != null, JobAnalysisService.isStale(job.getDescription(), latest));
    }

    /**
     * 删除（5.5）：逻辑删除，幂等；关联分析记录**保留**（《数据库设计》5.4/5.10）。
     *
     * 三种情况与简历一致：本人有效岗位 → 删除成功；重复删除 → 幂等成功；
     * 不存在或属于他人 → 404。
     */
    public void delete(Long userId, Long id) {
        // 刻意不过滤 status：已逻辑删除的行仍可查到，据此实现幂等
        Job job = jobMapper.selectOne(Wrappers.<Job>lambdaQuery()
                .eq(Job::getId, id)
                .eq(Job::getUserId, userId));
        if (job == null) {
            throw BusinessException.notFound("岗位不存在");
        }
        if (job.getStatus() != null && job.getStatus() == Job.STATUS_DELETED) {
            return;
        }
        jobMapper.update(null, Wrappers.<Job>lambdaUpdate()
                .eq(Job::getId, id)
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE)
                .set(Job::getStatus, Job.STATUS_DELETED));
    }

    // ---------------------------------------------------------------- 归属校验与查询

    /**
     * 归属校验：查不到（不存在或属于他人）一律 404，不区分二者以避免暴露资源存在性。
     *
     * public：岗位分析（{@link JobAnalysisService}）与 M8 的 Agent 工具复用同一条路径。
     */
    public Job getOwnedJob(Long userId, Long id) {
        Job job = jobMapper.selectOne(Wrappers.<Job>lambdaQuery()
                .eq(Job::getId, id)
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE));
        if (job == null) {
            throw BusinessException.notFound("岗位不存在");
        }
        return job;
    }

    @Override
    public Job getJobById(Long id) {
        return jobMapper.selectById(id);
    }

    @Override
    public List<Job> listOwnedJobs(Long userId) {
        return jobMapper.selectList(Wrappers.<Job>lambdaQuery()
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE)
                .orderByDesc(Job::getCreatedAt)
                .orderByDesc(Job::getId));
    }

    @Override
    public List<Job> listJobsByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jobMapper.selectList(Wrappers.<Job>lambdaQuery().in(Job::getId, ids));
    }

    @Override
    public long countOwnedJobs(Long userId) {
        Long count = jobMapper.selectCount(Wrappers.<Job>lambdaQuery()
                .eq(Job::getUserId, userId)
                .eq(Job::getStatus, Job.STATUS_ACTIVE));
        return count == null ? 0L : count;
    }

    /** 最新成功分析（详情与 stale 判定用；分析接口的复用判断走 JobAnalysisService 的同款查询） */
    private JobAnalysis findLatestSuccess(Long jobId) {
        return jobAnalysisMapper.selectOne(Wrappers.<JobAnalysis>lambdaQuery()
                .eq(JobAnalysis::getJobId, jobId)
                .eq(JobAnalysis::getStatus, JobAnalysis.STATUS_SUCCESS)
                .orderByDesc(JobAnalysis::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 批量查询当页岗位的"最新成功分析"（列表拼 analyzed/stale 用）。
     *
     * 沿用 M4 的「两次查询、无自定义 SQL、无 N+1」范式：只对**当页** ID 做一次 IN 查询
     * （走 idx_job_analysis_hash 的最左前缀 job_id），再在内存中按 jobId 取 versionNo 最大者。
     * 只 select 判定所需的三列（source_hash 是 stale 比对的关键）。
     */
    private Map<Long, JobAnalysis> latestSuccessByJobIds(List<Job> jobs) {
        List<Long> ids = jobs.stream().map(Job::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<JobAnalysis> analyses = jobAnalysisMapper.selectList(Wrappers.<JobAnalysis>lambdaQuery()
                .select(JobAnalysis::getJobId, JobAnalysis::getVersionNo, JobAnalysis::getSourceHash)
                .in(JobAnalysis::getJobId, ids)
                .eq(JobAnalysis::getStatus, JobAnalysis.STATUS_SUCCESS));

        Map<Long, JobAnalysis> latestByJobId = new HashMap<>();
        for (JobAnalysis analysis : analyses) {
            JobAnalysis current = latestByJobId.get(analysis.getJobId());
            if (current == null || analysis.getVersionNo() > current.getVersionNo()) {
                latestByJobId.put(analysis.getJobId(), analysis);
            }
        }
        return latestByJobId;
    }

    // ---------------------------------------------------------------- 装配

    private JobView toView(Job job, JobAnalysis latest) {
        JobView view = new JobView();
        view.setId(job.getId());
        view.setCompanyName(job.getCompanyName());
        view.setJobName(job.getJobName());
        view.setApplyUrl(job.getApplyUrl());
        view.setProgressStatus(progressOf(job));
        view.setCreatedAt(job.getCreatedAt());
        view.setUpdatedAt(job.getUpdatedAt());
        view.setAnalyzed(latest != null);
        // isStale 内部对 latest == null 短路为 false（analyzed=false 时 stale 恒 false）
        view.setStale(JobAnalysisService.isStale(job.getDescription(), latest));
        return view;
    }

    private JobDetail toDetail(Job job, boolean analyzed, boolean stale) {
        JobDetail detail = new JobDetail();
        detail.setId(job.getId());
        detail.setCompanyName(job.getCompanyName());
        detail.setJobName(job.getJobName());
        detail.setDescription(job.getDescription());
        detail.setApplyUrl(job.getApplyUrl());
        detail.setProgressStatus(progressOf(job));
        detail.setCreatedAt(job.getCreatedAt());
        detail.setUpdatedAt(job.getUpdatedAt());
        detail.setAnalyzed(analyzed);
        detail.setStale(stale);
        return detail;
    }

    /**
     * 库列 NOT NULL DEFAULT 'PENDING'，正常读出的值不会为 null；
     * 兜底仅为装配稳健（如迁移前的老行、测试夹具未赋值），避免下游拿到 null 状态。
     */
    private static String progressOf(Job job) {
        return job.getProgressStatus() == null ? Job.PROGRESS_PENDING : job.getProgressStatus();
    }

    /** 空串/全空白 → null（清空公司名的语义，与 UserService.normalizeNickname 一致）；其余 trim */
    private static String normalizeCompanyName(String companyName) {
        return normalizeOptional(companyName);
    }

    /**
     * 投递链接（M19）：空串/全空白 → null（清空语义同公司名）；非空时协议必须是 http/https。
     *
     * 协议白名单是**安全约束而非格式偏好**：该值最终由前端 {@code window.open} 打开，
     * 若放任 {@code javascript:} 等伪协议入库，点击「投递」即执行脚本。
     * 比较大小写不敏感（`HTTP://` 是合法写法）；不合法抛 40000（通用参数错误），
     * 不用 40001——后者是 Bean Validation 的字段级校验出口，本校验在其之后。
     */
    private static String normalizeApplyUrl(String applyUrl) {
        String url = normalizeOptional(applyUrl);
        if (url == null) {
            return null;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            throw BusinessException.badRequest("投递链接需以 http:// 或 https:// 开头");
        }
        return url;
    }

    /** 可空文本的统一归一：空串/全空白 → null，其余 trim（公司名与投递链接共用） */
    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
