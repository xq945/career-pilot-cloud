package com.careerpilot.service;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.Hashing;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.dto.ai.JobAnalysisAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.service.ai.JobAiService;

/**
 * 岗位分析业务编排（《API接口设计》5.6–5.7、《数据库设计》5.2–5.3），与
 * {@link ResumeAnalysisService} 同构：每次调用都重新分析（不做复用判断，2026-10-13
 * 用户拍板）、版本递增、失败不落库、唯一索引兜底。
 *
 * 岗位独有的两点：
 * 1. **过期（stale）判定**——岗位描述可修改，因此"最新成功分析的 source_hash
 *    与当前 description 不一致"是有意义的业务状态。判定收敛为静态纯函数
 *    {@link #isStale}，由列表、详情、分析查询三处共用（{@link #sameSource}
 *    是其唯一表达式）。
 * 2. **重试耗尽的兜底必须同源校验**——见 {@link #generateAndSave} 的注释：
 *    M4 的无条件兜底依赖"简历文本不可变"，岗位不成立，故此处为**有意的**差异。
 *
 * 写路径不加 @Transactional（模型调用与解析在 INSERT 之前，任何 502 都先于写入抛出，
 * 天然满足"失败不入库"；单条 INSERT 本身原子）。
 *
 * 本类的 {@link #analyze(Job)} 是 REST 与 M8 的 Agent 工具（{@code @Tool}）**共用入口**，
 * 用户身份一律由调用方从认证上下文取得后传入，绝不由模型提供。
 */
@Service
public class JobAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(JobAnalysisService.class);

    /** 版本号并发冲突的最大尝试次数（《数据库设计》5.2：SELECT MAX+1 配合唯一索引重试） */
    private static final int MAX_INSERT_ATTEMPTS = 3;

    private final JobService jobService;

    private final JobAiService jobAiService;

    private final JobAnalysisMapper jobAnalysisMapper;

    public JobAnalysisService(JobService jobService, JobAiService jobAiService,
                              JobAnalysisMapper jobAnalysisMapper) {
        this.jobService = jobService;
        this.jobAiService = jobAiService;
        this.jobAnalysisMapper = jobAnalysisMapper;
    }

    // ---------------------------------------------------------------- 对外入口

    /** 发起分析（5.6）：归属校验后进入核心流程 */
    public JobAnalysisDetail analyze(Long userId, Long jobId) {
        return analyze(jobService.getOwnedJob(userId, jobId));
    }

    /**
     * 核心入口（M8 的 @Tool 与后续里程碑复用）：入参为**已归属校验**的岗位实体。
     *
     * 流程：描述非空校验 → 计算 source_hash → 调用模型、清洗、落新版本。
     * **不做复用判断**：每次调用都重新分析（《API接口设计》5.6，2026-10-13 用户拍板）；
     * 描述未变也生成新版本，过期判定（{@link #isStale}）不受影响。
     */
    public JobAnalysisDetail analyze(Job job) {
        String description = job.getDescription();
        if (description == null || description.isBlank()) {
            throw BusinessException.badRequest("岗位描述为空，无法分析");
        }
        String sourceHash = Hashing.sha256Hex(description);
        return generateAndSave(job, sourceHash);
    }

    /** 获取最新一版成功分析（5.7）：无结果时返回 analyzed=false（HTTP 200，不是 404） */
    public JobAnalysisView getLatest(Long userId, Long jobId) {
        Job job = jobService.getOwnedJob(userId, jobId);
        JobAnalysis latest = findLatestSuccess(job.getId());
        return latest == null
                ? JobAnalysisView.notAnalyzed()
                : JobAnalysisView.of(toDetail(latest), isStale(job.getDescription(), latest));
    }

    // ---------------------------------------------------------------- 过期（stale）判定

    /**
     * 内容指纹一致性：当前描述的 hash 与最新成功分析的 source_hash 是否相同。
     *
     * **唯一表达式**：过期判定（{@link #isStale}）只能经由本方法，
     * 保证列表 / 详情 / 分析查询三处的口径不漂移。
     */
    public static boolean sameSource(String currentHash, JobAnalysis latestSuccess) {
        return latestSuccess != null && currentHash.equals(latestSuccess.getSourceHash());
    }

    /**
     * 分析是否过期（《API接口设计》5.2/5.7）：已有分析，但当前描述与它依据的描述不一致
     * （描述在分析后被修改过）。无分析时恒为 false（无分析谈不上过期）。
     *
     * 供列表（{@link JobService}）与详情、分析查询共用；M6 创建匹配时的过期拦截复用同一判定。
     */
    public static boolean isStale(String description, JobAnalysis latestSuccess) {
        return latestSuccess != null && !sameSource(Hashing.sha256Hex(description), latestSuccess);
    }

    // ---------------------------------------------------------------- 生成与落库

    /** 调用模型 → 清洗 → 落库。模型调用只发生一次，版本冲突重试期间不重复计费。 */
    private JobAnalysisDetail generateAndSave(Job job, String sourceHash) {
        AiCallResult<JobAnalysisAiOutput> result = jobAiService.analyze(job.getDescription());

        JobAnalysis entity = new JobAnalysis();
        entity.setJobId(job.getId());
        entity.setSourceHash(sourceHash);
        entity.setStatus(JobAnalysis.STATUS_SUCCESS);
        entity.setModelName(result.modelName());
        // 显式设置创建时间：MP 的 insert 不会回读数据库默认值（同 ResumeAnalysisService）
        entity.setCreatedAt(LocalDateTime.now());
        applyOutput(entity, result.value());

        for (int attempt = 1; attempt <= MAX_INSERT_ATTEMPTS; attempt++) {
            entity.setId(null);
            entity.setVersionNo(nextVersionNo(job.getId()));
            try {
                jobAnalysisMapper.insert(entity);
                log.info("岗位分析已落库：jobId={} analysisId={} versionNo={} model={} 耗时={}ms",
                        job.getId(), entity.getId(), entity.getVersionNo(),
                        result.modelName(), result.elapsedMillis());
                return toDetail(entity);
            }
            catch (DuplicateKeyException ex) {
                // uk_job_analysis_version 兜底：并发下另一个请求已占用该版本号
                log.warn("分析版本号并发冲突，重试：jobId={} versionNo={} 第{}次",
                        job.getId(), entity.getVersionNo(), attempt);
            }
        }
        // 重试耗尽。注意：**不能照抄 M4 的无条件兜底**——M4 返回最新已有结果的正当性来自
        // "简历文本入库后不可变"（兜底必同源）；岗位描述可修改，最新成功分析可能与本次
        // 提交的描述**不同源**，直接返回会把旧描述的结论当作本次结果。故此处必须校验同源，
        // 不同源只能失败（用户可重试）。这是岗位与简历的有意差异，勿改回 M4 的写法。
        JobAnalysis fallback = findLatestSuccess(job.getId());
        if (fallback != null && sourceHash.equals(fallback.getSourceHash())) {
            return toDetail(fallback);
        }
        throw new BusinessException(50000, 500, "分析结果保存失败，请稍后重试");
    }

    // ---------------------------------------------------------------- 查询与工具

    /**
     * 最新成功分析（过期判定、重试兜底与 5.7 共用同一查询路径）。
     *
     * public：M6 创建匹配（读取匹配依据与过期拦截）与 M8 的 Agent 工具复用同一查询，
     * 避免各调用方自行拼 Wrapper 造成口径漂移。
     */
    public JobAnalysis findLatestSuccess(Long jobId) {
        return jobAnalysisMapper.selectOne(Wrappers.<JobAnalysis>lambdaQuery()
                .eq(JobAnalysis::getJobId, jobId)
                .eq(JobAnalysis::getStatus, JobAnalysis.STATUS_SUCCESS)
                .orderByDesc(JobAnalysis::getVersionNo)
                .last("LIMIT 1"));
    }

    /**
     * 下一版本号。**不带 status 过滤**：唯一索引覆盖所有状态，
     * 取 MAX 可避免历史状态行的版本号被重复占用。
     */
    private int nextVersionNo(Long jobId) {
        JobAnalysis latest = jobAnalysisMapper.selectOne(Wrappers.<JobAnalysis>lambdaQuery()
                .select(JobAnalysis::getVersionNo)
                .eq(JobAnalysis::getJobId, jobId)
                .orderByDesc(JobAnalysis::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    private static void applyOutput(JobAnalysis entity, JobAnalysisAiOutput output) {
        entity.setResponsibilities(output.getResponsibilities());
        entity.setRequiredSkills(output.getRequiredSkills());
        entity.setPreferredSkills(output.getPreferredSkills());
        entity.setTechnologyStack(output.getTechnologyStack());
        entity.setSoftSkills(output.getSoftSkills());
        entity.setEducationRequirement(output.getEducationRequirement());
        entity.setExperienceRequirement(output.getExperienceRequirement());
        entity.setSummary(output.getSummary());
    }

    private static JobAnalysisDetail toDetail(JobAnalysis entity) {
        JobAnalysisDetail detail = new JobAnalysisDetail();
        detail.setAnalysisId(entity.getId());
        detail.setJobId(entity.getJobId());
        detail.setVersionNo(entity.getVersionNo());
        detail.setStatus(entity.getStatus());
        detail.setResponsibilities(entity.getResponsibilities());
        detail.setRequiredSkills(entity.getRequiredSkills());
        detail.setPreferredSkills(entity.getPreferredSkills());
        detail.setTechnologyStack(entity.getTechnologyStack());
        detail.setSoftSkills(entity.getSoftSkills());
        detail.setEducationRequirement(entity.getEducationRequirement());
        detail.setExperienceRequirement(entity.getExperienceRequirement());
        detail.setSummary(entity.getSummary());
        detail.setModelName(entity.getModelName());
        detail.setCreatedAt(entity.getCreatedAt());
        return detail;
    }

}
