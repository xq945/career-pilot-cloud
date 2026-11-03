package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
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
public class MatchService {

    private static final Logger log = LoggerFactory.getLogger(MatchService.class);

    private final MatchRecordMapper matchRecordMapper;

    private final ResumeMapper resumeMapper;

    private final JobMapper jobMapper;

    private final ResumeAnalysisMapper resumeAnalysisMapper;

    private final JobAnalysisMapper jobAnalysisMapper;

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchScoringService matchScoringService;

    private final MatchAiService matchAiService;

    public MatchService(MatchRecordMapper matchRecordMapper, ResumeMapper resumeMapper, JobMapper jobMapper,
                        ResumeAnalysisMapper resumeAnalysisMapper, JobAnalysisMapper jobAnalysisMapper,
                        ResumeService resumeService, JobService jobService,
                        ResumeAnalysisService resumeAnalysisService, JobAnalysisService jobAnalysisService,
                        MatchScoringService matchScoringService, MatchAiService matchAiService) {
        this.matchRecordMapper = matchRecordMapper;
        this.resumeMapper = resumeMapper;
        this.jobMapper = jobMapper;
        this.resumeAnalysisMapper = resumeAnalysisMapper;
        this.jobAnalysisMapper = jobAnalysisMapper;
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
        // 外键为 ON DELETE RESTRICT，且源资源与分析行只做逻辑删除/不删除——这些行必然存在
        Resume resume = resumeMapper.selectById(record.getResumeId());
        Job job = jobMapper.selectById(record.getJobId());
        ResumeAnalysis resumeAnalysis = resumeAnalysisMapper.selectById(record.getResumeAnalysisId());
        JobAnalysis jobAnalysis = jobAnalysisMapper.selectById(record.getJobAnalysisId());
        return toDetail(record, resume.getName(), resumeAnalysis.getVersionNo(),
                job.getJobName(), job.getCompanyName(), jobAnalysis.getVersionNo());
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
        return resumeMapper.selectList(Wrappers.<Resume>lambdaQuery()
                        .select(Resume::getId, Resume::getName)
                        .in(Resume::getId, ids))
                .stream().collect(Collectors.toMap(Resume::getId, Function.identity()));
    }

    /** 当页岗位（**不过滤 status**，同上） */
    private Map<Long, Job> jobsByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getJobId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobMapper.selectList(Wrappers.<Job>lambdaQuery()
                        .select(Job::getId, Job::getJobName, Job::getCompanyName)
                        .in(Job::getId, ids))
                .stream().collect(Collectors.toMap(Job::getId, Function.identity()));
    }

    /** 当页记录所依据的简历分析版本号（match_record 无 version 列，从分析表读取） */
    private Map<Long, Integer> resumeVersionsByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getResumeAnalysisId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> versions = new HashMap<>();
        for (ResumeAnalysis analysis : resumeAnalysisMapper.selectList(Wrappers.<ResumeAnalysis>lambdaQuery()
                .select(ResumeAnalysis::getId, ResumeAnalysis::getVersionNo)
                .in(ResumeAnalysis::getId, ids))) {
            versions.put(analysis.getId(), analysis.getVersionNo());
        }
        return versions;
    }

    /** 当页记录所依据的岗位分析版本号 */
    private Map<Long, Integer> jobVersionsByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getJobAnalysisId).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, Integer> versions = new HashMap<>();
        for (JobAnalysis analysis : jobAnalysisMapper.selectList(Wrappers.<JobAnalysis>lambdaQuery()
                .select(JobAnalysis::getId, JobAnalysis::getVersionNo)
                .in(JobAnalysis::getId, ids))) {
            versions.put(analysis.getId(), analysis.getVersionNo());
        }
        return versions;
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
