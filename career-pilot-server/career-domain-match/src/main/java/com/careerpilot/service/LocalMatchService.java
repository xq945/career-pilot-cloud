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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchView;
import com.careerpilot.dto.ai.MatchExplanationAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.service.ai.MatchAiService;

/**
 * 匹配服务（《API接口设计》6.1–6.3、《系统架构设计》6.2）。
 *
 * <p>流程：归属校验 → 双方分析存在性 → **岗位分析过期拦截** → 结构校验（S2）→
 * 确定性评分 → 模型解释（含清洗与账本补全）→ 落库 → 装配响应。
 *
 * <p>**模型不能覆盖总分**：落库的 match_score 只取自 {@link MatchScoringResult#totalScore()}，
 * 模型输出类型不含分数字段（见 {@link MatchAiService}）。
 *
 * <p>写路径不加 @Transactional（同 M3–M5）：模型调用在 INSERT 之前，任何 502 都先于写入抛出，
 * 天然满足"失败不入库"；单条 INSERT 本身原子。本表无唯一索引，无需版本冲突重试。
 *
 * <p>{@link #create(Resume, Job, ResumeAnalysis, JobAnalysis)} 是 REST 与 M8 的 Agent 工具
 * （{@code matchResumeToJob}）**共用入口**；两条入口的差异（REST 缺分析返回 400、
 * Agent 工具代为补齐）由各自的上层处理，见《系统架构设计》6.2。
 */
@Service
@ConditionalOnProperty(prefix = "app.match", name = "mode", havingValue = "local", matchIfMissing = true)
public class LocalMatchService implements MatchService {

    private static final Logger log = LoggerFactory.getLogger(LocalMatchService.class);

    private final MatchRecordMapper matchRecordMapper;

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchScoringService matchScoringService;

    private final MatchAiService matchAiService;

    public LocalMatchService(MatchRecordMapper matchRecordMapper,
                             ResumeService resumeService, JobService jobService,
                             ResumeAnalysisService resumeAnalysisService, JobAnalysisService jobAnalysisService,
                             MatchScoringService matchScoringService, MatchAiService matchAiService) {
        this.matchRecordMapper = matchRecordMapper;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
        this.matchScoringService = matchScoringService;
        this.matchAiService = matchAiService;
    }

    // ---------------------------------------------------------------- 创建（6.1）

    /**
     * REST 入口（6.1）：缺分析或分析过期时返回 400（提示先完成/重新分析，**不自动补齐**）。
     *
     * 每次调用创建一条新记录（保留历史，便于对比简历修改效果）。
     */
    public MatchDetail create(Long userId, MatchCreateRequest request) {
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
        // 岗位分析过期拦截（V2.1，R04）：判定复用单点 isStale，与列表的 stale 口径一致
        if (JobAnalysisService.isStale(job.getDescription(), jobAnalysis)) {
            throw BusinessException.badRequest("岗位描述已更新，请先重新分析岗位");
        }
        return create(resume, job, resumeAnalysis, jobAnalysis);
    }

    /**
     * Agent 入口（阶段 7 从工具类下沉）：缺分析或岗位分析过期时**自动补齐**再匹配。
     *
     * <p>与 REST 的差异是刻意设计（《系统架构设计》§6.2）：REST 返回 400 提示先分析；
     * 工具入口自动完成分析，无需用户提前操作。拆分后工具只发一次远程调用，
     * 由本服务完成"补齐 → 匹配"，避免"工具侧先分析、服务侧再分析"的重复模型调用。
     */
    @Override
    public MatchDetail createForAgent(Long userId, Long resumeId, Long jobId) {
        Resume resume = resumeService.getOwnedResume(userId, resumeId);
        Job job = jobService.getOwnedJob(userId, jobId);

        ResumeAnalysis resumeAnalysis = resumeAnalysisService.findLatestSuccess(resumeId);
        if (resumeAnalysis == null) {
            resumeAnalysisService.analyze(resume);
            resumeAnalysis = resumeAnalysisService.findLatestSuccess(resumeId);
        }
        JobAnalysis jobAnalysis = jobAnalysisService.findLatestSuccess(jobId);
        // 注意：isStale 对"无分析"恒为 false（无分析谈不上过期），必须显式判空
        if (jobAnalysis == null || JobAnalysisService.isStale(job.getDescription(), jobAnalysis)) {
            jobAnalysisService.analyze(job);
            jobAnalysis = jobAnalysisService.findLatestSuccess(jobId);
        }
        if (resumeAnalysis == null || jobAnalysis == null) {
            throw BusinessException.aiUnavailable("分析结果生成失败，请稍后重试");
        }
        return create(resume, job, resumeAnalysis, jobAnalysis);
    }

    /**
     * 核心入口（M8 的 Agent 工具复用）：结构校验 → 计分 → 模型解释 → 落库 → 装配。
     *
     * @param resume         已通过归属校验的简历
     * @param job            已通过归属校验且分析未过期的岗位
     * @param resumeAnalysis 简历最新成功分析
     * @param jobAnalysis    岗位最新成功分析且与当前描述同源
     */
    public MatchDetail create(Resume resume, Job job, ResumeAnalysis resumeAnalysis, JobAnalysis jobAnalysis) {
        // 结构校验（审查报告 S2）：读取入库的分析结果后、使用前做一次清洗，防脏数据扩散；
        // 只清洗内存对象并记脱敏日志，不写回数据库
        AnalysisStructureCheck.sanitize(resumeAnalysis, "match");
        AnalysisStructureCheck.sanitize(jobAnalysis, "match");

        MatchScoringResult scoring = matchScoringService.score(jobAnalysis, resumeAnalysis);
        // 模型调用：失败抛 502 系列，先于任何写入（失败不入库）
        MatchExplanationAiOutput explanation = matchAiService.explain(jobAnalysis, resumeAnalysis, scoring).value();

        MatchRecord record = new MatchRecord();
        record.setUserId(resume.getUserId());
        record.setResumeId(resume.getId());
        record.setJobId(job.getId());
        record.setResumeAnalysisId(resumeAnalysis.getId());
        record.setJobAnalysisId(jobAnalysis.getId());
        // 冗余版本号（阶段 7）：列表/详情展示不再回查简历/岗位服务
        record.setResumeVersionNo(resumeAnalysis.getVersionNo());
        record.setJobVersionNo(jobAnalysis.getVersionNo());
        // 分数只取自规则计算的结果（模型输出无分数字段，构造上不可能覆盖）
        record.setMatchScore(scoring.totalScore());
        record.setMatchedItems(explanation.getMatchedItems());
        record.setPartialItems(explanation.getPartialItems());
        record.setMissingItems(explanation.getMissingItems());
        record.setDimensionScores(scoring.dimensionScores());
        record.setSuggestions(explanation.getSuggestions());
        record.setSummary(explanation.getSummary());
        // 显式设置创建时间：MP 的 insert 不会回读数据库默认值（同 M3–M5），裁到秒与 DATETIME(0) 对齐
        record.setCreatedAt(LocalDateTime.now().withNano(0));
        matchRecordMapper.insert(record);

        log.info("匹配记录已落库：matchId={} resumeId={} jobId={} resumeAnalysisId={} jobAnalysisId={} score={}",
                record.getId(), record.getResumeId(), record.getJobId(),
                record.getResumeAnalysisId(), record.getJobAnalysisId(), record.getMatchScore());

        return toDetail(record, resume.getName(), resumeAnalysis.getVersionNo(),
                job.getJobName(), job.getCompanyName(), jobAnalysis.getVersionNo());
    }

    // ---------------------------------------------------------------- 列表与详情（6.2 / 6.3）

    /** 列表（6.2）：created_at 倒序（id 倒序作稳定排序的 tie-breaker） */
    public PageResult<MatchView> list(Long userId, PageQuery query) {
        Page<MatchRecord> page = new Page<>(query.getPage(), query.getSize());
        IPage<MatchRecord> result = matchRecordMapper.selectPage(page, Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getUserId, userId)
                .orderByDesc(MatchRecord::getCreatedAt)
                .orderByDesc(MatchRecord::getId));

        List<MatchRecord> records = result.getRecords();
        Map<Long, Resume> resumes = resumesByIds(records);
        Map<Long, Job> jobs = jobsByIds(records);
        Map<Long, Integer> resumeVersions = resumeVersionsByIds(records);
        Map<Long, Integer> jobVersions = jobVersionsByIds(records);

        List<MatchView> views = records.stream()
                .map(record -> toView(record, resumes.get(record.getResumeId()), jobs.get(record.getJobId()),
                        resumeVersions.get(record.getResumeAnalysisId()),
                        jobVersions.get(record.getJobAnalysisId())))
                .toList();
        return PageResult.of(result, views);
    }

    /** 详情（6.3）：结构与创建响应一致；不存在或非本人 → 404（不区分二者） */
    public MatchDetail detail(Long userId, Long id) {
        MatchRecord record = matchRecordMapper.selectOne(Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getId, id)
                .eq(MatchRecord::getUserId, userId));
        if (record == null) {
            throw BusinessException.notFound("匹配记录不存在");
        }
        // 源资源与分析行只做逻辑删除/不删除——这些行必然存在（阶段 6b：改由简历服务提供）
        Resume resume = resumeService.getResumeById(record.getResumeId());
        Job job = jobService.getJobById(record.getJobId());
        // 阶段 7：优先用冗余版本号；旧数据（null）回退到分析服务查询
        Integer resumeVersionNo = record.getResumeVersionNo();
        if (resumeVersionNo == null) {
            resumeVersionNo = resumeAnalysisService.getAnalysisById(record.getResumeAnalysisId()).getVersionNo();
        }
        Integer jobVersionNo = record.getJobVersionNo();
        if (jobVersionNo == null) {
            jobVersionNo = jobAnalysisService.getAnalysisById(record.getJobAnalysisId()).getVersionNo();
        }
        return toDetail(record, resume.getName(), resumeVersionNo,
                job.getJobName(), job.getCompanyName(), jobVersionNo);
    }

    // ---------------------------------------------------------------- 关联查询（当页批量，无 N+1）

    /**
     * 当页简历（**不过滤 status**：源资源逻辑删除后名称仍要显示，《API接口设计》6.2）。
     * 沿用 M4/M5 的「两次查询、无自定义 SQL、无 N+1」范式。
     */
    private Map<Long, Resume> resumesByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getResumeId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resumeService.listResumesByIds(ids)
                .stream().collect(Collectors.toMap(Resume::getId, Function.identity()));
    }

    /** 当页岗位（**不过滤 status**，同上） */
    private Map<Long, Job> jobsByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getJobId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobService.listJobsByIds(ids).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));
    }

    /**
     * 当页记录所依据的简历分析版本号：优先取 match_record 的冗余列（阶段 7），
     * 旧数据（null）才批量回查简历服务。
     */
    private Map<Long, Integer> resumeVersionsByIds(List<MatchRecord> records) {
        Map<Long, Integer> versions = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (MatchRecord record : records) {
            if (record.getResumeVersionNo() != null) {
                versions.put(record.getResumeAnalysisId(), record.getResumeVersionNo());
            }
            else {
                missingIds.add(record.getResumeAnalysisId());
            }
        }
        if (!missingIds.isEmpty()) {
            for (ResumeAnalysis analysis : resumeAnalysisService.getAnalysesByIds(
                    missingIds.stream().distinct().toList())) {
                versions.put(analysis.getId(), analysis.getVersionNo());
            }
        }
        return versions;
    }

    /** 当页记录所依据的岗位分析版本号（同上） */
    private Map<Long, Integer> jobVersionsByIds(List<MatchRecord> records) {
        Map<Long, Integer> versions = new HashMap<>();
        List<Long> missingIds = new ArrayList<>();
        for (MatchRecord record : records) {
            if (record.getJobVersionNo() != null) {
                versions.put(record.getJobAnalysisId(), record.getJobVersionNo());
            }
            else {
                missingIds.add(record.getJobAnalysisId());
            }
        }
        if (!missingIds.isEmpty()) {
            for (JobAnalysis analysis : jobAnalysisService.getAnalysesByIds(
                    missingIds.stream().distinct().toList())) {
                versions.put(analysis.getId(), analysis.getVersionNo());
            }
        }
        return versions;
    }

    // ---------------------------------------------------------------- 跨域查询（阶段 7）

    @Override
    public MatchRecord getOwnedMatch(Long userId, Long id) {
        MatchRecord record = matchRecordMapper.selectOne(Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getId, id)
                .eq(MatchRecord::getUserId, userId));
        if (record == null) {
            throw BusinessException.notFound("匹配记录不存在");
        }
        return record;
    }

    @Override
    public MatchRecord latestMatch(Long userId, Long resumeId, Long jobId) {
        return matchRecordMapper.selectOne(Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getUserId, userId)
                .eq(MatchRecord::getResumeId, resumeId)
                .eq(MatchRecord::getJobId, jobId)
                .orderByDesc(MatchRecord::getCreatedAt)
                .orderByDesc(MatchRecord::getId)
                .last("LIMIT 1"));
    }

    @Override
    public MatchRecord latestMatchForUser(Long userId) {
        return matchRecordMapper.selectOne(Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getUserId, userId)
                .orderByDesc(MatchRecord::getCreatedAt)
                .orderByDesc(MatchRecord::getId)
                .last("LIMIT 1"));
    }

    @Override
    public long countOwnedMatches(Long userId) {
        Long count = matchRecordMapper.selectCount(Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getUserId, userId));
        return count == null ? 0L : count;
    }

    @Override
    public Double bestMatchScore(Long userId) {
        MatchRecord best = matchRecordMapper.selectOne(Wrappers.<MatchRecord>lambdaQuery()
                .select(MatchRecord::getMatchScore)
                .eq(MatchRecord::getUserId, userId)
                .orderByDesc(MatchRecord::getMatchScore)
                .orderByDesc(MatchRecord::getId)
                .last("LIMIT 1"));
        return best == null ? null : best.getMatchScore().doubleValue();
    }

    @Override
    public List<MatchRecord> recentMatches(Long userId, int limit) {
        Page<MatchRecord> page = new Page<>(1, Math.max(1, limit));
        IPage<MatchRecord> result = matchRecordMapper.selectPage(page, Wrappers.<MatchRecord>lambdaQuery()
                .eq(MatchRecord::getUserId, userId)
                .orderByDesc(MatchRecord::getCreatedAt)
                .orderByDesc(MatchRecord::getId));
        return result.getRecords();
    }

    // ---------------------------------------------------------------- 装配

    private static MatchDetail toDetail(MatchRecord record, String resumeName, Integer resumeVersionNo,
                                        String jobName, String companyName, Integer jobVersionNo) {
        MatchDetail detail = new MatchDetail();
        detail.setId(record.getId());
        detail.setResumeId(record.getResumeId());
        detail.setResumeName(resumeName);
        detail.setResumeVersionNo(resumeVersionNo);
        detail.setJobId(record.getJobId());
        detail.setJobName(jobName);
        detail.setCompanyName(companyName);
        detail.setJobVersionNo(jobVersionNo);
        // BigDecimal → double：避免 JSON 出现 76.50 这类尾零（《API接口设计》6.1 的示例为 75.75）
        detail.setMatchScore(record.getMatchScore().doubleValue());
        detail.setMatchedItems(record.getMatchedItems());
        detail.setPartialItems(record.getPartialItems());
        detail.setMissingItems(record.getMissingItems());
        detail.setDimensionScores(record.getDimensionScores());
        detail.setSummary(record.getSummary());
        detail.setSuggestions(record.getSuggestions());
        detail.setCreatedAt(record.getCreatedAt());
        return detail;
    }

    private static MatchView toView(MatchRecord record, Resume resume, Job job,
                                    Integer resumeVersionNo, Integer jobVersionNo) {
        MatchView view = new MatchView();
        view.setId(record.getId());
        view.setResumeId(record.getResumeId());
        view.setResumeName(resume.getName());
        view.setResumeVersionNo(resumeVersionNo);
        view.setJobId(record.getJobId());
        view.setJobName(job.getJobName());
        view.setCompanyName(job.getCompanyName());
        view.setJobVersionNo(jobVersionNo);
        view.setMatchScore(record.getMatchScore().doubleValue());
        view.setCreatedAt(record.getCreatedAt());
        return view;
    }

}
