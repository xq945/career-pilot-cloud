package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.dto.OptimizationFailureIssue;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.EditSnapshot;
import com.careerpilot.dto.JobRequirements;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.OptimizedResumeFile;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;
import com.careerpilot.dto.ai.ResumeAnalysisAiOutput;
import com.careerpilot.dto.ai.ResumeOptimizationAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.ai.ResumeAiService;
import com.careerpilot.service.ai.ResumeOptimizationAiService;
import com.careerpilot.service.optimization.OptimizationFactCheckException;
import com.careerpilot.service.optimization.OptimizationStreamProducer;
import com.careerpilot.service.optimization.OptimizationTaskRunner;
import com.careerpilot.service.optimization.OptimizationTaskStateService;

/**
 * 简历定向优化服务（《API接口设计》§6A、《系统架构设计》§6.4）。
 *
 * <p>创建流程：归属校验 → 双方分析存在性 + 岗位分析过期拦截（同 §6.1 的 REST 语义，
 * **不自动补齐**）→ 复用 {@link MatchService#create(Resume, Job, ResumeAnalysis, JobAnalysis)}
 * 完成匹配（规则评分 + 模型解释，落 {@code match_record}）→ 优化生成（1 次模型调用）
 * → 版本号计算与落库 → 装配详情。
 *
 * <p>**失败不入库**：两次模型调用都在 INSERT 之前，任何 502 系列都先于写入抛出
 * （同 M3–M6 的写路径约定，本类不加 @Transactional——单条 INSERT 本身原子，
 * 唯一键冲突用重试解决，与 JobAnalysisService 的既有模式一致）。
 * 注意：匹配记录先于优化生成落库，优化生成失败时会留下一条**有效的** match_record
 * （它是完整的匹配分析，用户可在仪表盘看到）；重试会产生新的匹配记录，属可接受的降级。
 *
 * <p>**分数口径**：{@code matchScore} 取自优化前那条 match_record（由 {@link MatchScoringService}
 * 计算）；{@code scoreAfter} 在重新评估时由同一服务、同一份岗位分析重算。模型输出类型不含
 * 任何分数字段，构造上不可能覆盖分数。
 */
@Service
public class ResumeOptimizationService {

    private static final Logger log = LoggerFactory.getLogger(ResumeOptimizationService.class);

    /** 校验明细的反序列化器（只读自身写入的 JSON，不涉及用户输入） */
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 编辑历史保留上限（《数据库设计》5.11：超出丢弃最旧） */
    private static final int EDIT_HISTORY_LIMIT = 10;

    /** 版本号冲突的最大重试次数（唯一键兜底，沿用 JobAnalysisService 的模式） */
    private static final int VERSION_RETRY_LIMIT = 3;

    private final ResumeOptimizationMapper optimizationMapper;

    private final MatchRecordMapper matchRecordMapper;

    private final ResumeMapper resumeMapper;

    private final JobMapper jobMapper;

    private final ResumeAnalysisMapper resumeAnalysisMapper;

    private final JobAnalysisMapper jobAnalysisMapper;

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchService matchService;

    private final MatchScoringService matchScoringService;

    private final ResumeOptimizationAiService resumeOptimizationAiService;

    private final ResumeAiService resumeAiService;

    /** M16：任务执行体（同步降级与异步消费者共用） */
    private final OptimizationTaskRunner taskRunner;

    /** M16：任务状态机写路径 */
    private final OptimizationTaskStateService stateService;

    /** M16：任务生产者（异步开关关闭时为空——用 ObjectProvider 避免强制依赖） */
    private final ObjectProvider<OptimizationStreamProducer> streamProducerProvider;

    public ResumeOptimizationService(ResumeOptimizationMapper optimizationMapper,
                                     MatchRecordMapper matchRecordMapper, ResumeMapper resumeMapper,
                                     JobMapper jobMapper, ResumeAnalysisMapper resumeAnalysisMapper,
                                     JobAnalysisMapper jobAnalysisMapper,
                                     ResumeService resumeService, JobService jobService,
                                     ResumeAnalysisService resumeAnalysisService,
                                     JobAnalysisService jobAnalysisService,
                                     MatchService matchService, MatchScoringService matchScoringService,
                                     ResumeOptimizationAiService resumeOptimizationAiService,
                                     ResumeAiService resumeAiService,
                                     OptimizationTaskRunner taskRunner,
                                     OptimizationTaskStateService stateService,
                                     ObjectProvider<OptimizationStreamProducer> streamProducerProvider) {
        this.optimizationMapper = optimizationMapper;
        this.matchRecordMapper = matchRecordMapper;
        this.resumeMapper = resumeMapper;
        this.jobMapper = jobMapper;
        this.resumeAnalysisMapper = resumeAnalysisMapper;
        this.jobAnalysisMapper = jobAnalysisMapper;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
        this.matchService = matchService;
        this.matchScoringService = matchScoringService;
        this.resumeOptimizationAiService = resumeOptimizationAiService;
        this.resumeAiService = resumeAiService;
        this.taskRunner = taskRunner;
        this.stateService = stateService;
        this.streamProducerProvider = streamProducerProvider;
    }

    // ---------------------------------------------------------------- 创建（6A.1）

    /**
     * 创建优化（M16 起为**异步任务**，§6A.1）：缺分析或岗位分析过期 → 400（不自动补齐，校验保持同步）；
     * 校验通过后立即落 PENDING 任务行并入队，**模型调用（匹配解释 + 优化生成，共 2 次）在后台执行**，
     * 接口立刻返回（前端跳详情页轮询进度）。
     *
     * <p>两条降级路径（都走**同步执行**，与 M11 行为一致）：
     * <ul>
     *   <li>{@code app.optimization.async.enabled=false}（无 Redis 环境的回退开关）；</li>
     *   <li>入队失败（Redis 抖动等）——不把任务静默丢失，在本次请求内执行完。</li>
     *   <li><b>语义变更</b>：同步路径失败时**会留下 FAILED 记录**（PENDING 行已先落库），
     *       与 M11 的"失败不落库"不同——失败可见、可手动重试（§6A.7）。</li>
     * </ul>
     *
     * 每次调用创建一条新记录（同一简历+岗位的 versionNo 递增，保留历史）。
     */
    public ResumeOptimizationDetail create(Long userId, ResumeOptimizationCreateRequest request) {
        Resume resume = resumeService.getOwnedResume(userId, request.getResumeId());
        Job job = jobService.getOwnedJob(userId, request.getJobId());

        ResumeAnalysis resumeAnalysis = resumeAnalysisService.findLatestSuccess(resume.getId());
        if (resumeAnalysis == null) {
            throw BusinessException.badRequest("请先完成简历分析");
        }
        JobAnalysis jobAnalysis = jobAnalysisService.findLatestSuccess(job.getId());
        if (jobAnalysis == null) {
            throw BusinessException.badRequest("请先完成岗位分析");
        }
        if (JobAnalysisService.isStale(job.getDescription(), jobAnalysis)) {
            throw BusinessException.badRequest("岗位描述已更新，请先重新分析岗位");
        }

        // M16：先落 PENDING 任务行（模型调用移到后台任务；执行期会复核分析有效性，
        // 见 OptimizationTaskRunner——入队到执行之间岗位描述可能被改动）
        ResumeOptimization record = new ResumeOptimization();
        record.setUserId(userId);
        record.setResumeId(resume.getId());
        record.setJobId(job.getId());
        record.setStatus(ResumeOptimization.STATUS_PENDING);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        record.setCreatedAt(now);
        record.setUpdatedAt(now);
        insertWithVersionRetry(record);

        log.info("优化任务已创建：optimizationId={} resumeId={} jobId={} versionNo={}",
                record.getId(), record.getResumeId(), record.getJobId(), record.getVersionNo());

        if (!enqueueTask(userId, record.getId())) {
            runSynchronously(record.getId());
        }
        return toDetail(optimizationMapper.selectById(record.getId()));
    }

    // ---------------------------------------------------------------- 任务编排（M16）

    /** 入队；返回 false = 未启用异步或入队失败（调用方降级为同步执行） */
    private boolean enqueueTask(Long userId, Long optimizationId) {
        OptimizationStreamProducer producer = streamProducerProvider.getIfAvailable();
        if (producer == null) {
            log.info("异步链路未启用（app.optimization.async.enabled=false），本次同步执行：optimizationId={}",
                    optimizationId);
            return false;
        }
        return producer.send(new OptimizationStreamProducer.OptimizationTask(optimizationId, userId));
    }

    /**
     * 同步执行（降级路径与 enabled=false 共用）：条件领取 → 执行 → 失败落 FAILED 后异常上抛。
     * 领取失败（理论不可达：行刚插入且处于 PENDING）按内部错误处理。
     */
    private void runSynchronously(Long optimizationId) {
        String attemptId = stateService.markProcessing(optimizationId);
        if (attemptId == null) {
            throw BusinessException.internalError("任务状态异常，请稍后重试");
        }
        try {
            taskRunner.run(optimizationId, attemptId);
        }
        catch (RuntimeException ex) {
            // 事实校验失败时一并保存完整问题清单（V1.13）；erro_message 仍是 500 字符汇总
            String failureDetail = ex instanceof OptimizationFactCheckException factCheck
                    ? factCheck.failureDetailJson()
                    : null;
            stateService.fail(optimizationId, attemptId,
                    ex.getMessage() == null ? "任务执行失败" : ex.getMessage(), failureDetail);
            // O-16：任务行已落库并置为 FAILED，错误响应带上 ID，前端据此提示
            // “已创建但执行失败”，而不是“未产生记录”。
            throw withOptimizationId(ex, optimizationId);
        }
    }

    /**
     * 同步降级执行失败的业务异常包装：保留原业务码与文案，附加 {@code optimizationId}。
     * 非业务异常（如 NPE）统一按 50000 包装，任务记录同样已存在。
     */
    private static BusinessException withOptimizationId(RuntimeException ex, Long optimizationId) {
        Map<String, Object> data = Map.of("optimizationId", optimizationId);
        if (ex instanceof BusinessException business) {
            return new BusinessException(business.getCode(), business.getHttpStatus(),
                    business.getMessage(), data);
        }
        // 非业务异常包装成 BusinessException 后不会走 handleUnexpected，堆栈在此保留
        log.error("同步优化任务执行失败：optimizationId={}", optimizationId, ex);
        return new BusinessException(50000, 500, "任务执行失败，请稍后重试", data);
    }

    // ---------------------------------------------------------------- 手动重试（6A.7）

    /**
     * 手动重试失败的任务（§6A.7，M16）：仅 FAILED 可重试；重置为 PENDING 后重新入队
     * （入队失败同样降级为同步执行）。
     */
    public ResumeOptimizationDetail retry(Long userId, Long id) {
        ResumeOptimization record = getOwned(userId, id);
        if (!ResumeOptimization.STATUS_FAILED.equals(record.getStatus())) {
            throw BusinessException.badRequest("仅失败的任务可以重试（当前状态：" + record.getStatus() + "）");
        }
        if (!stateService.resetForRetry(id)) {
            throw BusinessException.badRequest("任务状态已变化，请刷新后重试");
        }
        log.info("优化任务手动重试：optimizationId={}", id);
        if (!enqueueTask(userId, id)) {
            runSynchronously(id);
        }
        return toDetail(optimizationMapper.selectById(id));
    }

    /**
     * 插入并处理版本号并发冲突：{@code uk_resume_optimization_version} 兜底，
     * 冲突时取新号重试（沿用 JobAnalysisService 的既有模式）。
     */
    private void insertWithVersionRetry(ResumeOptimization record) {
        for (int attempt = 1; ; attempt++) {
            record.setVersionNo(nextVersionNo(record.getResumeId(), record.getJobId()));
            try {
                optimizationMapper.insert(record);
                return;
            }
            catch (DuplicateKeyException ex) {
                if (attempt >= VERSION_RETRY_LIMIT) {
                    throw ex;
                }
                log.warn("优化版本号并发冲突，重试：resumeId={} jobId={} versionNo={} 第{}次",
                        record.getResumeId(), record.getJobId(), record.getVersionNo(), attempt);
            }
        }
    }

    private int nextVersionNo(Long resumeId, Long jobId) {
        ResumeOptimization latest = optimizationMapper.selectOne(Wrappers.<ResumeOptimization>lambdaQuery()
                .select(ResumeOptimization::getVersionNo)
                .eq(ResumeOptimization::getResumeId, resumeId)
                .eq(ResumeOptimization::getJobId, jobId)
                .orderByDesc(ResumeOptimization::getVersionNo)
                .last("LIMIT 1"));
        return latest == null || latest.getVersionNo() == null ? 1 : latest.getVersionNo() + 1;
    }

    // ---------------------------------------------------------------- 列表与详情（6A.2 / 6A.3）

    /** 列表（6A.2）：created_at 倒序（id 倒序作稳定排序的 tie-breaker） */
    public PageResult<ResumeOptimizationView> list(Long userId, PageQuery query) {
        Page<ResumeOptimization> page = new Page<>(query.getPage(), query.getSize());
        IPage<ResumeOptimization> result = optimizationMapper.selectPage(page, Wrappers.<ResumeOptimization>lambdaQuery()
                .eq(ResumeOptimization::getUserId, userId)
                .orderByDesc(ResumeOptimization::getCreatedAt)
                .orderByDesc(ResumeOptimization::getId));

        List<ResumeOptimization> records = result.getRecords();
        Map<Long, Resume> resumes = resumesByIds(records);
        Map<Long, Job> jobs = jobsByIds(records);
        Map<Long, MatchRecord> matches = matchesByIds(records);

        List<ResumeOptimizationView> views = records.stream()
                .map(record -> toView(record, resumes.get(record.getResumeId()), jobs.get(record.getJobId()),
                        record.getMatchRecordId() == null ? null : matches.get(record.getMatchRecordId())))
                .toList();
        return PageResult.of(result, views);
    }

    /** 详情（6A.3）：不存在或非本人 → 404（不区分二者） */
    public ResumeOptimizationDetail detail(Long userId, Long id) {
        return toDetail(getOwned(userId, id));
    }

    // ---------------------------------------------------------------- 保存编辑稿（6A.4）

    /**
     * 保存用户编辑稿：写入 {@code edited_content}，**不覆盖** {@code optimized_content}；
     * 覆盖前把旧稿推入编辑历史（上限 10 版）。内容与当前稿一致时不产生历史版本。
     */
    public ResumeOptimizationDetail saveContent(Long userId, Long id, String content) {
        ResumeOptimization record = getOwned(userId, id);
        requireSuccess(record, "编辑");
        String normalized = content.strip();
        String current = currentContent(record);
        if (normalized.equals(current)) {
            // 无实际变化：不写库、不产生历史版本，直接返回当前详情
            return toDetail(record);
        }

        List<EditSnapshot> history = record.getEditHistory() == null
                ? new ArrayList<>()
                : new ArrayList<>(record.getEditHistory());
        // 只有"用户稿被用户稿覆盖"才入历史；首版是模型原稿，它本身留在 optimized_content 中
        if (record.getEditedContent() != null && !record.getEditedContent().isBlank()) {
            history.add(new EditSnapshot(record.getEditedContent(), LocalDateTime.now().withNano(0)));
        }
        while (history.size() > EDIT_HISTORY_LIMIT) {
            history.remove(0);
        }

        record.setEditedContent(normalized);
        record.setEditHistory(history);
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        // 用 updateById（走实体的 TypeHandler）写 JSON 列；null 字段被默认策略忽略，不会误清空
        optimizationMapper.updateById(record);

        log.info("优化稿已保存：optimizationId={} 历史版本数={}", record.getId(), history.size());
        return toDetail(record);
    }

    // ---------------------------------------------------------------- 重新评估（6A.5）

    /**
     * 重新评估优化后匹配度：对优化稿重新做结构化分析（1 次模型调用），
     * 复用**优化前那一条 match_record 的岗位分析**（保证前后口径一致），
     * 按同一评分规则重算并写入 {@code score_after} 等四列。
     *
     * 每次调用都真重跑、不复用（同 §4.5 的 V2.10 拍板）；失败时不写入任何列。
     */
    public ResumeOptimizationDetail evaluate(Long userId, Long id) {
        ResumeOptimization record = getOwned(userId, id);
        requireSuccess(record, "评估");
        String content = currentContent(record);

        // 与优化前同源的岗位分析（外键 RESTRICT 保证存在）
        MatchRecord matchRecord = matchRecordMapper.selectById(record.getMatchRecordId());
        JobAnalysis jobAnalysis = jobAnalysisService.getAnalysisById(matchRecord.getJobAnalysisId());

        // 优化稿重新结构化分析（模型调用 + 既有清洗规则），仅落到本记录的 optimized_analysis，
        // 不写入 resume_analysis（优化稿不是一份独立简历，见《数据库设计》5.11）
        ResumeAnalysisAiOutput analysis = resumeAiService.analyze(content).value();
        ResumeAnalysis analysisEntity = toResumeAnalysis(analysis);
        AnalysisStructureCheck.sanitize(analysisEntity, "optimization-evaluate");

        MatchScoringResult scoring = matchScoringService.score(jobAnalysis, analysisEntity);

        record.setOptimizedAnalysis(analysis);
        record.setScoreAfter(scoring.totalScore());
        record.setDimensionScoresAfter(scoring.dimensionScores());
        record.setEvaluatedAt(LocalDateTime.now().withNano(0));
        record.setUpdatedAt(LocalDateTime.now().withNano(0));
        optimizationMapper.updateById(record);

        log.info("优化后评估完成：optimizationId={} jobAnalysisId={} scoreAfter={}",
                record.getId(), jobAnalysis.getId(), scoring.totalScore());
        return toDetail(record);
    }

    // ---------------------------------------------------------------- 导出（6A.6）

    /**
     * 读取导出内容（用户编辑稿优先，为空时取模型原稿）与建议文件名。
     * DOCX 的字节生成由 Controller 交给导出器完成（本方法不落盘）。
     */
    public OptimizedResumeFile readExport(Long userId, Long id) {
        ResumeOptimization record = getOwned(userId, id);
        requireSuccess(record, "导出");
        Resume resume = resumeMapper.selectById(record.getResumeId());
        Job job = jobService.getJobById(record.getJobId());
        String fileName = safeFileName((resume == null ? "简历" : resume.getName())
                + "-" + (job == null ? "岗位" : job.getJobName()) + "-优化稿");
        return new OptimizedResumeFile(fileName + ".docx", currentContent(record));
    }

    /** 文件名清洗：剔除路径与非法字符、截断到合理长度（后缀由调用方补齐） */
    private static String safeFileName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]", "_").strip();
        if (cleaned.isEmpty()) {
            cleaned = "简历-优化稿";
        }
        return cleaned.length() > 120 ? cleaned.substring(0, 120) : cleaned;
    }

    // ---------------------------------------------------------------- 内部方法

    /**
     * 归属校验：查不到（不存在或属于他人）一律 404，不区分二者以避免暴露资源存在性。
     * （M12 起对面试模块开放：创建面试会话时校验优化来源的归属。）
     */
    public ResumeOptimization getOwned(Long userId, Long id) {
        ResumeOptimization record = optimizationMapper.selectOne(Wrappers.<ResumeOptimization>lambdaQuery()
                .eq(ResumeOptimization::getId, id)
                .eq(ResumeOptimization::getUserId, userId));
        if (record == null) {
            throw BusinessException.notFound("优化记录不存在");
        }
        return record;
    }

    /**
     * 当前生效内容：用户编辑稿优先，无编辑稿时回落模型原稿（展示、评估、导出同口径）。
     * （M12 起对面试模块开放：出题的候选人简历块取同一口径。）
     */
    public static String currentContent(ResumeOptimization record) {
        String edited = record.getEditedContent();
        return edited == null || edited.isBlank() ? record.getOptimizedContent() : edited;
    }

    /** 当页简历（**不过滤 status**：源资源逻辑删除后名称仍要显示，同 §6.2） */
    private Map<Long, Resume> resumesByIds(List<ResumeOptimization> records) {
        List<Long> ids = records.stream().map(ResumeOptimization::getResumeId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resumeMapper.selectList(Wrappers.<Resume>lambdaQuery()
                        .select(Resume::getId, Resume::getName)
                        .in(Resume::getId, ids))
                .stream().collect(Collectors.toMap(Resume::getId, Function.identity()));
    }

    /** 当页岗位（**不过滤 status**） */
    private Map<Long, Job> jobsByIds(List<ResumeOptimization> records) {
        List<Long> ids = records.stream().map(ResumeOptimization::getJobId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobService.listJobsByIds(ids).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
    }

    /** 当页匹配记录（取优化前分数） */
    private Map<Long, MatchRecord> matchesByIds(List<ResumeOptimization> records) {
        // M16：任务未完成的行 match_record_id 为 null——过滤后再批量查询（否则 IN (null) 无匹配、装配 NPE）
        List<Long> ids = records.stream()
                .map(ResumeOptimization::getMatchRecordId)
                .filter(id -> id != null)
                .distinct().toList();
        if (ids.isEmpty()) {
            // O-09：必须返回**允许 null key 查询**的 Map（HashMap）；Map.of() 在
            // get(null) 时抛 NPE，当页全部为 PENDING/PROCESSING/FAILED 时列表 500。
            return new HashMap<>();
        }
        Map<Long, MatchRecord> matches = new HashMap<>();
        for (MatchRecord record : matchRecordMapper.selectList(Wrappers.<MatchRecord>lambdaQuery()
                .select(MatchRecord::getId, MatchRecord::getMatchScore)
                .in(MatchRecord::getId, ids))) {
            matches.put(record.getId(), record);
        }
        return matches;
    }

    /**
     * 装配详情：优化数据的每一项都从**落库的快照**读取（不重新调用模型）。
     *
     * <p>M16：任务未完成时 {@code match_record_id} 为 null（匹配尚未发生）——此时只装配
     * 来源与任务控制字段（状态 / 阶段 / 失败原因），分数、内容、差距等**产物字段保持为空**
     * （前端按状态渲染进度或失败态）。
     */
    private ResumeOptimizationDetail toDetail(ResumeOptimization record) {
        Resume resume = resumeMapper.selectById(record.getResumeId());
        Job job = jobService.getJobById(record.getJobId());
        if (resume == null || job == null) {
            // 外键 RESTRICT + 逻辑删除保证两行存在；此分支仅为防御
            throw BusinessException.internalError("优化记录的来源资源缺失");
        }

        ResumeOptimizationDetail detail = new ResumeOptimizationDetail();
        detail.setId(record.getId());
        detail.setResumeId(record.getResumeId());
        detail.setResumeName(resume.getName());
        detail.setJobId(record.getJobId());
        detail.setJobName(job.getJobName());
        detail.setCompanyName(job.getCompanyName());
        detail.setVersionNo(record.getVersionNo());
        detail.setModelName(record.getModelName());
        detail.setStatus(record.getStatus());
        // M16：任务控制字段（详情页轮询进度 / 失败文案与重试入口）
        detail.setStage(record.getStage());
        detail.setErrorMessage(record.getErrorMessage());
        detail.setFailureIssues(parseFailureIssues(record));
        detail.setCreatedAt(record.getCreatedAt());
        detail.setUpdatedAt(record.getUpdatedAt());

        // M16：任务未完成（PENDING/PROCESSING/FAILED）——匹配与优化产物尚未产生
        if (record.getMatchRecordId() == null) {
            return detail;
        }

        // 以下装配同既有实现（外键 RESTRICT 保证引用的分析行存在）
        MatchRecord matchRecord = matchRecordMapper.selectById(record.getMatchRecordId());
        JobAnalysis jobAnalysis = jobAnalysisService.getAnalysisById(matchRecord.getJobAnalysisId());
        ResumeAnalysis resumeAnalysis = resumeAnalysisMapper.selectById(matchRecord.getResumeAnalysisId());
        detail.setResumeVersionNo(resumeAnalysis.getVersionNo());
        detail.setJobVersionNo(jobAnalysis.getVersionNo());
        detail.setMatchId(record.getMatchRecordId());
        detail.setMatchScore(matchRecord.getMatchScore().doubleValue());
        detail.setDimensionScores(matchRecord.getDimensionScores());
        detail.setMatchedItems(matchRecord.getMatchedItems());
        detail.setPartialItems(matchRecord.getPartialItems());
        detail.setMissingItems(matchRecord.getMissingItems());
        detail.setJobRequirements(toRequirements(jobAnalysis));
        detail.setResumeAdvantages(resumeAnalysis.getAdvantages());
        detail.setOptimizedContent(record.getOptimizedContent());
        detail.setEditedContent(record.getEditedContent());
        detail.setEditHistory(record.getEditHistory());
        detail.setChanges(record.getChanges());
        detail.setGapQuestions(record.getGapQuestions());
        detail.setScoreAfter(record.getScoreAfter() == null ? null : record.getScoreAfter().doubleValue());
        detail.setDimensionScoresAfter(record.getDimensionScoresAfter());
        detail.setEvaluatedAt(record.getEvaluatedAt());
        return detail;
    }

    /**
     * 解析失败明细（V1.13）。明细是本服务写入的受控 JSON，解析失败说明数据损坏，
     * 记 WARN 后按无明细返回，不影响失败态的其余字段展示。
     */
    private static List<OptimizationFailureIssue> parseFailureIssues(ResumeOptimization record) {
        String json = record.getFailureDetail();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json,
                    MAPPER.getTypeFactory().constructCollectionType(List.class,
                            OptimizationFailureIssue.class));
        }
        catch (RuntimeException ex) {
            log.warn("优化失败明细解析失败：optimizationId={} 异常={}",
                    record.getId(), ex.getClass().getSimpleName());
            return null;
        }
    }

    /** M16：产物类操作（编辑 / 评估 / 导出 / 面试来源装配）要求任务已成功完成 */
    private static void requireSuccess(ResumeOptimization record, String action) {
        if (!ResumeOptimization.STATUS_SUCCESS.equals(record.getStatus())) {
            throw BusinessException.badRequest(
                    "优化尚未生成完成，暂不能" + action + "（当前状态：" + record.getStatus() + "）");
        }
    }

    private static ResumeOptimizationView toView(ResumeOptimization record, Resume resume, Job job,
                                                 MatchRecord matchRecord) {
        ResumeOptimizationView view = new ResumeOptimizationView();
        view.setId(record.getId());
        view.setResumeId(record.getResumeId());
        view.setResumeName(resume.getName());
        view.setJobId(record.getJobId());
        view.setJobName(job.getJobName());
        view.setCompanyName(job.getCompanyName());
        view.setVersionNo(record.getVersionNo());
        // M16：未完成行没有匹配记录——列表页按状态标签渲染，分数保持 null
        view.setMatchScore(matchRecord == null ? null : matchRecord.getMatchScore().doubleValue());
        view.setScoreAfter(record.getScoreAfter() == null ? null : record.getScoreAfter().doubleValue());
        view.setStatus(record.getStatus());
        view.setCreatedAt(record.getCreatedAt());
        view.setUpdatedAt(record.getUpdatedAt());
        return view;
    }

    private static JobRequirements toRequirements(JobAnalysis jobAnalysis) {
        JobRequirements requirements = new JobRequirements();
        requirements.setSummary(jobAnalysis.getSummary());
        requirements.setResponsibilities(jobAnalysis.getResponsibilities());
        requirements.setRequiredSkills(jobAnalysis.getRequiredSkills());
        requirements.setPreferredSkills(jobAnalysis.getPreferredSkills());
        requirements.setTechnologyStack(jobAnalysis.getTechnologyStack());
        requirements.setSoftSkills(jobAnalysis.getSoftSkills());
        requirements.setEducationRequirement(jobAnalysis.getEducationRequirement());
        requirements.setExperienceRequirement(jobAnalysis.getExperienceRequirement());
        return requirements;
    }

    /**
     * 模型输出的结构化分析 → 实体形态（仅供评分服务使用，**不落库**）。
     * MatchScoringService 的入参类型是实体，这里做一次内存转换。
     */
    private static ResumeAnalysis toResumeAnalysis(ResumeAnalysisAiOutput output) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setSummary(output.getSummary());
        analysis.setSkills(output.getSkills());
        analysis.setEducation(output.getEducation());
        analysis.setProjects(output.getProjects());
        analysis.setExperience(output.getExperience());
        analysis.setAdvantages(output.getAdvantages());
        analysis.setWeaknesses(output.getWeaknesses());
        analysis.setSuggestions(output.getSuggestions());
        return analysis;
    }

}
