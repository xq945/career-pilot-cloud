package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.dto.DashboardGapItem;
import com.careerpilot.dto.DashboardRecentMatch;
import com.careerpilot.dto.DashboardRecentTask;
import com.careerpilot.dto.DashboardSummary;
import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.entity.ResumeOptimization;

/**
 * 仪表盘汇总服务（《API接口设计》9.1）。
 *
 * <p>只读、只服务仪表盘单一页面，不做通用化；各计数为独立查询、**第一版不做缓存**。
 * 全部查询带 user_id 过滤（与其余接口的归属校验要求一致）。
 *
 * <p>口径与各模块列表严格一致（这是验收标准「仪表盘所有数字与各模块实际数据一致」的前提）：
 * 简历 / 岗位只计 {@code status = 1}；分析只计成功态；岗位过期复用
 * {@link JobAnalysisService#isStale} 单点（因此 {@code jobStaleCount ⊆ jobAnalyzedCount} 天然成立）；
 * 面试 {@code status = 2}（已取消）不计入任何计数。
 *
 * <p>无自定义 SQL、无 N+1：沿用 M4–M6 的「两次查询 + 内存装配」范式；
 * 空集合一律先短路（不发 {@code IN ()}）。
 */
@Service
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** 最近匹配记录条数（9.1 定死 5） */
    private static final int RECENT_MATCH_LIMIT = 5;

    /** 最近任务：每类取的条数（9.1，M13） */
    private static final int RECENT_TASK_PER_KIND = 3;

    /** 最近任务：合并后的总上限（9.1，M13） */
    private static final int RECENT_TASK_LIMIT = 6;

    /** 最近任务的类型取值（9.1，M13） */
    private static final String TASK_TYPE_RESUME_ANALYSIS = "RESUME_ANALYSIS";

    private static final String TASK_TYPE_JOB_ANALYSIS = "JOB_ANALYSIS";

    private static final String TASK_TYPE_OPTIMIZATION = "OPTIMIZATION";

    private static final String TASK_TYPE_INTERVIEW = "INTERVIEW";

    /** 能力差距概览条数上限（9.1 定死 3：missing 在前、partial 在后） */
    private static final int TOP_GAP_LIMIT = 3;

    /** topGaps 的 type 取值（明细项无 type 列，由来源列表决定） */
    private static final String GAP_TYPE_MISSING = "MISSING";

    private static final String GAP_TYPE_PARTIAL = "PARTIAL";

    private final ResumeService resumeService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobService jobService;

    private final JobAnalysisService jobAnalysisService;

    private final MatchService matchService;

    private final ResumeOptimizationService resumeOptimizationService;

    /** 阶段 8：面试域已拆出，计数与最近会话经服务接口读取（remote/local 由配置决定） */
    private final InterviewService interviewService;

    /** 阶段 10：30 秒短缓存（事件可精确失效） */
    private final DashboardCache dashboardCache;

    /**
     * 阶段 10：分区并发执行器。虚拟线程适合这种"全是一次远程 IO"的扇出；
     * 每任务自建 SecurityContext（Feign 拦截器需要当前用户签发内部令牌）。
     */
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public DashboardService(ResumeService resumeService, ResumeAnalysisService resumeAnalysisService,
                            JobService jobService, JobAnalysisService jobAnalysisService,
                            MatchService matchService, ResumeOptimizationService resumeOptimizationService,
                            InterviewService interviewService, DashboardCache dashboardCache) {
        this.resumeService = resumeService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobService = jobService;
        this.jobAnalysisService = jobAnalysisService;
        this.matchService = matchService;
        this.resumeOptimizationService = resumeOptimizationService;
        this.interviewService = interviewService;
        this.dashboardCache = dashboardCache;
    }

    @PreDestroy
    void shutdownExecutor() {
        executor.close();
    }

    /**
     * 汇总（9.1）：读缓存 → 五个分区**并发**装配 → 写缓存。
     *
     * <p>分区（resume / job / match / interview / tasks）互相独立：任一远程服务故障只会
     * 把该分区降级为默认值，并把名称写入 {@code degradedSections}，其余卡片照常返回。
     */
    public DashboardSummary summary(Long userId) {
        DashboardSummary cached = dashboardCache.get(userId);
        if (cached != null) {
            log.debug("Dashboard 缓存命中：userId={}", userId);
            return cached;
        }
        DashboardSummary summary = assemble(userId);
        dashboardCache.put(userId, summary);
        return summary;
    }

    private DashboardSummary assemble(Long userId) {
        Queue<String> degraded = new ConcurrentLinkedQueue<>();

        CompletableFuture<int[]> resumeFuture = section(userId, "resume", degraded,
                () -> new int[]{(int) resumeService.countOwnedResumes(userId), resumeAnalyzedCount(userId)},
                new int[]{0, 0});
        CompletableFuture<int[]> jobFuture = section(userId, "job", degraded, () -> {
            JobCounts counts = jobCounts(userId);
            return new int[]{(int) jobService.countOwnedJobs(userId), counts.analyzed(), counts.stale()};
        }, new int[]{0, 0, 0});
        CompletableFuture<MatchPart> matchFuture = section(userId, "match", degraded,
                () -> new MatchPart((int) matchService.countOwnedMatches(userId),
                        matchService.bestMatchScore(userId), recentMatches(userId), topGaps(userId)),
                MatchPart.EMPTY);
        CompletableFuture<int[]> interviewFuture = section(userId, "interview", degraded,
                () -> new int[]{
                        (int) interviewService.countOwnedSessions(userId),
                        (int) interviewService.countOwnedSessionsByStatus(
                                userId, InterviewSession.STATUS_ONGOING),
                        (int) interviewService.countOwnedSessionsByStatus(
                                userId, InterviewSession.STATUS_COMPLETED)},
                new int[]{0, 0, 0});
        CompletableFuture<List<DashboardRecentTask>> tasksFuture = section(userId, "recentTasks",
                degraded, () -> recentTasks(userId), List.of());

        int[] resume = resumeFuture.join();
        int[] job = jobFuture.join();
        MatchPart match = matchFuture.join();
        int[] interview = interviewFuture.join();

        DashboardSummary summary = new DashboardSummary();
        summary.setResumeCount(resume[0]);
        summary.setResumeAnalyzedCount(resume[1]);
        summary.setJobCount(job[0]);
        summary.setJobAnalyzedCount(job[1]);
        summary.setJobStaleCount(job[2]);
        summary.setMatchCount(match.count());
        summary.setBestMatchScore(match.bestScore());
        summary.setInterviewCount(interview[0]);
        summary.setInterviewOngoingCount(interview[1]);
        summary.setInterviewCompletedCount(interview[2]);
        summary.setRecentMatches(match.recentMatches());
        summary.setTopGaps(match.topGaps());
        summary.setRecentTasks(tasksFuture.join());
        summary.setDegradedSections(List.copyOf(degraded));
        return summary;
    }

    /**
     * 在独立线程执行一个分区：复制当前 Authentication 与 MDC（traceId）到工作线程，
     * 失败时降级为默认值并记录分区名（部分失败不影响其他卡片）。
     */
    private <T> CompletableFuture<T> section(Long userId, String name, Queue<String> degraded,
                                             Supplier<T> supplier, T fallback) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        return CompletableFuture.supplyAsync(() -> {
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            if (mdc != null) {
                MDC.setContextMap(mdc);
            }
            try {
                return supplier.get();
            }
            catch (RuntimeException ex) {
                degraded.add(name);
                log.warn("Dashboard 分区降级：section={} userId={} 异常={}",
                        name, userId, ex.getClass().getSimpleName());
                return fallback;
            }
            finally {
                SecurityContextHolder.clearContext();
                MDC.clear();
            }
        }, executor);
    }

    /** 匹配分区结果（一次并发任务内取齐，减少线程调度） */
    private record MatchPart(int count, Double bestScore,
                             List<DashboardRecentMatch> recentMatches,
                             List<DashboardGapItem> topGaps) {

        private static final MatchPart EMPTY =
                new MatchPart(0, null, List.of(), List.of());
    }

    // ---------------------------------------------------------------- 简历 / 岗位计数

    /**
     * 已有成功分析的**有效简历**数（9.1 口径：有效简历 ∩ 有成功分析，去重）。
     *
     * <p>阶段 6b 起口径下沉到简历服务（{@code countAnalyzedResumes}）：不能直接数
     * resume_analysis 行——该表没有 user_id 列，且同一简历可能有多版本分析，
     * 需先取本人有效简历再按 resume_id 去重。
     */
    private int resumeAnalyzedCount(Long userId) {
        return (int) resumeAnalysisService.countAnalyzedResumes(userId);
    }

    /** 岗位的 analyzed / stale 计数：由岗位分析服务按同一口径计算。 */
    private JobCounts jobCounts(Long userId) {
        return new JobCounts(
                (int) jobAnalysisService.countAnalyzedJobs(userId),
                (int) jobAnalysisService.countStaleJobs(userId));
    }

    // ---------------------------------------------------------------- 匹配

    /** 最近 5 条匹配（created_at 倒序、id 倒序兜底；内联名称不过滤 status） */
    private List<DashboardRecentMatch> recentMatches(Long userId) {
        List<MatchRecord> records = matchService.recentMatches(userId, RECENT_MATCH_LIMIT);
        if (records.isEmpty()) {
            return List.of();
        }
        Map<Long, Resume> resumes = resumesByIds(records);
        Map<Long, Job> jobs = jobsByIds(records);
        return records.stream().map(record -> {
            DashboardRecentMatch item = new DashboardRecentMatch();
            item.setId(record.getId());
            item.setResumeId(record.getResumeId());
            item.setResumeName(resumes.get(record.getResumeId()).getName());
            item.setJobId(record.getJobId());
            item.setJobName(jobs.get(record.getJobId()).getJobName());
            item.setCompanyName(jobs.get(record.getJobId()).getCompanyName());
            item.setMatchScore(record.getMatchScore().doubleValue());
            item.setCreatedAt(record.getCreatedAt());
            return item;
        }).toList();
    }

    /** 最近一次匹配的差距项：missing 在前、partial 在后，最多 3 条；无记录时空数组 */
    private List<DashboardGapItem> topGaps(Long userId) {
        MatchRecord latest = matchService.latestMatchForUser(userId);
        if (latest == null) {
            return List.of();
        }
        List<DashboardGapItem> gaps = new ArrayList<>();
        appendGaps(gaps, latest.getMissingItems(), GAP_TYPE_MISSING);
        appendGaps(gaps, latest.getPartialItems(), GAP_TYPE_PARTIAL);
        return gaps.size() > TOP_GAP_LIMIT ? List.copyOf(gaps.subList(0, TOP_GAP_LIMIT)) : gaps;
    }

    /** 明细项 → 差距项投影（text 与 dimension 原样转出；null 列表安全） */
    private static void appendGaps(List<DashboardGapItem> gaps, List<MatchItem> items, String type) {
        if (items == null) {
            return;
        }
        for (MatchItem item : items) {
            DashboardGapItem gap = new DashboardGapItem();
            gap.setType(type);
            gap.setText(item.getText());
            gap.setDimension(item.getDimension());
            gaps.add(gap);
        }
    }

    // ---------------------------------------------------------------- 最近任务（9.1，M13）

    /**
     * 最近任务时间线：简历分析 / 岗位分析 / 优化 / 面试四类各取最近
     * {@value #RECENT_TASK_PER_KIND} 条，合并后按时间倒序取前 {@value #RECENT_TASK_LIMIT} 条。
     *
     * <p>分析类任务只取**有效资源**（简历 / 岗位未被逻辑删除）的最新成功分析——任务线点击
     * 会跳转对应资源页，已删除资源的任务会形成死链；优化与面试记录本身没有删除语义，
     * 全量参与（其来源资源即使已删，详情页也能承载）。
     *
     * <p>排序：{@code happenedAt} 倒序（Java 稳定排序，同秒任务保持类型归集顺序，可断言）。
     */
    private List<DashboardRecentTask> recentTasks(Long userId) {
        List<DashboardRecentTask> tasks = new ArrayList<>();
        appendResumeAnalysisTasks(tasks, userId);
        appendJobAnalysisTasks(tasks, userId);
        appendOptimizationTasks(tasks, userId);
        appendInterviewTasks(tasks, userId);
        // nullsLast 为防御（DB 的 created_at NOT NULL，仅容错异常数据）：倒序、null 恒在末尾
        tasks.sort(Comparator.nullsLast(
                Comparator.comparing(DashboardRecentTask::getHappenedAt).reversed()));
        return tasks.size() > RECENT_TASK_LIMIT
                ? List.copyOf(tasks.subList(0, RECENT_TASK_LIMIT))
                : tasks;
    }

    /** 简历分析任务：本人有效简历的最近成功分析（标题 = 简历名） */
    private void appendResumeAnalysisTasks(List<DashboardRecentTask> tasks, Long userId) {
        List<Resume> resumes = resumeService.listOwnedResumes(userId);
        if (resumes.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = resumes.stream()
                .collect(Collectors.toMap(Resume::getId, Resume::getName));
        // recentAnalyses 的口径与拆分前一致：本人有效简历的成功分析、created_at 倒序、取前 N 条
        List<ResumeAnalysis> analyses = resumeAnalysisService.recentAnalyses(userId, RECENT_TASK_PER_KIND);
        for (ResumeAnalysis analysis : analyses) {
            tasks.add(task(TASK_TYPE_RESUME_ANALYSIS, nameById.get(analysis.getResumeId()),
                    analysis.getResumeId(), analysis.getCreatedAt()));
        }
    }

    /** 岗位分析任务：本人有效岗位的最近成功分析（标题 = 岗位名） */
    private void appendJobAnalysisTasks(List<DashboardRecentTask> tasks, Long userId) {
        List<Job> jobs = jobService.listOwnedJobs(userId);
        if (jobs.isEmpty()) {
            return;
        }
        Map<Long, String> nameById = jobs.stream()
                .collect(Collectors.toMap(Job::getId, Job::getJobName));
        List<JobAnalysis> analyses = jobAnalysisService.recentAnalyses(userId, RECENT_TASK_PER_KIND);
        for (JobAnalysis analysis : analyses) {
            tasks.add(task(TASK_TYPE_JOB_ANALYSIS, nameById.get(analysis.getJobId()),
                    analysis.getJobId(), analysis.getCreatedAt()));
        }
    }

    /** 优化任务：标题为「{简历名} · {岗位名}」（名称不过滤 status，源删除后仍显示） */
    private void appendOptimizationTasks(List<DashboardRecentTask> tasks, Long userId) {
        // 口径由匹配服务保证：本人、SUCCESS、created_at 倒序、取前 N 条
        List<ResumeOptimization> optimizations =
                resumeOptimizationService.recentSuccessOptimizations(userId, RECENT_TASK_PER_KIND);
        if (optimizations.isEmpty()) {
            return;
        }
        Map<Long, String> resumeNames = resumeNamesByIds(optimizations.stream()
                .map(ResumeOptimization::getResumeId).distinct().toList());
        Map<Long, String> jobNames = jobNamesByIds(optimizations.stream()
                .map(ResumeOptimization::getJobId).distinct().toList());
        for (ResumeOptimization optimization : optimizations) {
            String title = resumeNames.get(optimization.getResumeId()) + " · "
                    + jobNames.get(optimization.getJobId());
            tasks.add(task(TASK_TYPE_OPTIMIZATION, title, optimization.getId(),
                    optimization.getCreatedAt()));
        }
    }

    /** 面试任务：标题即会话标题（会话无删除语义，全量参与） */
    private void appendInterviewTasks(List<DashboardRecentTask> tasks, Long userId) {
        List<InterviewSession> sessions = interviewService.recentSessions(userId, RECENT_TASK_PER_KIND);
        for (InterviewSession session : sessions) {
            tasks.add(task(TASK_TYPE_INTERVIEW, session.getTitle(), session.getId(),
                    session.getCreatedAt()));
        }
    }

    /** 按 id 批量取简历名（**不过滤 status**：源资源逻辑删除后名称仍要显示） */
    private Map<Long, String> resumeNamesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return resumeService.listResumesByIds(List.copyOf(ids))
                .stream().collect(Collectors.toMap(Resume::getId, Resume::getName));
    }

    /** 按 id 批量取岗位名（**不过滤 status**，同上） */
    private Map<Long, String> jobNamesByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return jobService.listJobsByIds(List.copyOf(ids))
                .stream().collect(Collectors.toMap(Job::getId, Job::getJobName));
    }

    /** 最近任务项工厂 */
    private static DashboardRecentTask task(String type, String title, Long refId,
                                            LocalDateTime happenedAt) {
        DashboardRecentTask item = new DashboardRecentTask();
        item.setType(type);
        item.setTitle(title);
        item.setRefId(refId);
        item.setHappenedAt(happenedAt);
        return item;
    }

    // ---------------------------------------------------------------- 关联查询（当页批量，无 N+1）

    /** 当页记录关联的简历（**不过滤 status**：源资源逻辑删除后名称仍要显示，同 6.2） */
    private Map<Long, Resume> resumesByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getResumeId).distinct().toList();
        return resumeService.listResumesByIds(ids)
                .stream().collect(Collectors.toMap(Resume::getId, Function.identity()));
    }

    /** 当页记录关联的岗位（**不过滤 status**，同上） */
    private Map<Long, Job> jobsByIds(List<MatchRecord> records) {
        List<Long> ids = records.stream().map(MatchRecord::getJobId).distinct().toList();
        return jobService.listJobsByIds(ids)
                .stream().collect(Collectors.toMap(Job::getId, Function.identity()));
    }

    /** 岗位 analyzed / stale 两个计数的载体（一次查询算出，避免重复扫表） */
    private record JobCounts(int analyzed, int stale) {
    }

}
