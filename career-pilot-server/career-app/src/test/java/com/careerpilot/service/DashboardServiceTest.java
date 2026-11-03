package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.common.Hashing;
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
import com.careerpilot.mapper.InterviewSessionMapper;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;

/**
 * 仪表盘汇总的护栏（《API接口设计》9.1）。
 *
 * 重点断言三类不可回退的行为：**口径与各模块列表一致**（简历/岗位只计 status=1、
 * 分析只计成功态、stale 复用 {@link JobAnalysisService#isStale} 单点、面试取消态不计入细分计数）、
 * **空数据边界**（计数全 0、bestMatchScore=null、两个空数组）、**空集合不发 IN 查询**。
 *
 * 真库特有的行为（JSON 列回读、SQL 侧排序与 LIMIT、与各模块列表的交叉核对）
 * 按《测试与验收方案》1.1 放接口层脚本（tools/verify/m9-dashboard-api.mjs）。
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceTest {

    private static final Long USER_ID = 7L;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private ResumeAnalysisMapper resumeAnalysisMapper;

    @Mock
    private JobMapper jobMapper;

    @Mock
    private JobAnalysisMapper jobAnalysisMapper;

    @Mock
    private MatchRecordMapper matchRecordMapper;

    @Mock
    private InterviewSessionMapper interviewSessionMapper;

    @Mock
    private ResumeOptimizationMapper resumeOptimizationMapper;

    @InjectMocks
    private DashboardService dashboardService;

    // ---------------------------------------------------------------- 空数据边界

    @Test
    void summary_emptyAccount_zeroCountsAndNullBoundaries() {
        when(resumeMapper.selectList(any())).thenReturn(List.of());
        when(jobMapper.selectList(any())).thenReturn(List.of());
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        assertEquals(0, summary.getResumeCount());
        assertEquals(0, summary.getResumeAnalyzedCount());
        assertEquals(0, summary.getJobCount());
        assertEquals(0, summary.getJobAnalyzedCount());
        assertEquals(0, summary.getJobStaleCount());
        assertEquals(0, summary.getMatchCount());
        // 无匹配记录：最高分为 null（不是 0）
        assertNull(summary.getBestMatchScore());
        assertEquals(0, summary.getInterviewCount());
        assertEquals(0, summary.getInterviewOngoingCount());
        assertEquals(0, summary.getInterviewCompletedCount());
        // 无记录时三个数组为空（不是 null）
        assertTrue(summary.getRecentTasks().isEmpty());
        assertTrue(summary.getRecentMatches().isEmpty());
        assertTrue(summary.getTopGaps().isEmpty());
        // 空集合先短路：不对分析表发 IN 查询
        verify(resumeAnalysisMapper, never()).selectList(any());
        verify(jobAnalysisMapper, never()).selectList(any());
    }

    // ---------------------------------------------------------------- 简历 / 岗位计数口径

    @Test
    void summary_resumeAnalyzedCount_dedupesMultipleVersions() {
        when(resumeMapper.selectList(any())).thenReturn(List.of(
                resume(11L, "A"), resume(12L, "B"), resume(13L, "C")));
        // 11 号有两个版本的成功分析（只算一份简历），12 号一个，13 号没有
        when(resumeAnalysisMapper.selectList(any())).thenReturn(List.of(
                resumeAnalysis(11L), resumeAnalysis(11L), resumeAnalysis(12L)));
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        assertEquals(2, summary.getResumeAnalyzedCount());
    }

    @Test
    void summary_jobCounts_noAnalysisAnalyzedAndStale() {
        when(jobMapper.selectList(any())).thenReturn(List.of(
                job(21L, "岗位A", "描述A"),
                job(22L, "岗位B", "描述B"),
                job(23L, "岗位C", "描述C-新")));
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(
                jobAnalysis(22L, 1, Hashing.sha256Hex("描述B")),
                jobAnalysis(23L, 1, Hashing.sha256Hex("描述C-旧"))));
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        // 无分析的岗位不计 analyzed；23 号的描述与最新分析不同源 → stale
        assertEquals(2, summary.getJobAnalyzedCount());
        assertEquals(1, summary.getJobStaleCount());
    }

    @Test
    void summary_jobCounts_usesLatestVersionForStale() {
        when(jobMapper.selectList(any())).thenReturn(List.of(job(23L, "岗位", "描述-新版")));
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(
                jobAnalysis(23L, 1, Hashing.sha256Hex("描述-旧版")),
                jobAnalysis(23L, 2, Hashing.sha256Hex("描述-新版"))));
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        // 取 versionNo 最大者（v2 与当前描述同源）→ 不过期
        assertEquals(1, summary.getJobAnalyzedCount());
        assertEquals(0, summary.getJobStaleCount());
    }

    // ---------------------------------------------------------------- 匹配：最高分与最近记录

    @Test
    void summary_bestMatchScore_trimsTrailingZero() {
        when(matchRecordMapper.selectOne(any())).thenReturn(matchRecord(701L, new BigDecimal("82.40")));
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        assertEquals(82.4, summary.getBestMatchScore());
    }

    @Test
    void summary_recentMatches_mapsEightFieldsAndKeepsDeletedSourceNames() {
        Page<MatchRecord> page = new Page<>(1, 5);
        MatchRecord newest = matchRecord(702L, new BigDecimal("91.5"));
        newest.setCreatedAt(LocalDateTime.of(2026, 10, 12, 10, 0));
        MatchRecord older = matchRecord(701L, new BigDecimal("80.0"));
        older.setCreatedAt(LocalDateTime.of(2026, 10, 11, 9, 0));
        page.setRecords(List.of(newest, older));
        when(matchRecordMapper.selectPage(any(), any())).thenReturn(page);

        // 源资源已逻辑删除：关联查询不过滤 status，名称仍须显示（同 6.2 的可见性规则）
        Resume deletedResume = resume(11L, "Java后端简历");
        deletedResume.setStatus(Resume.STATUS_DELETED);
        when(resumeMapper.selectList(any())).thenReturn(List.of(deletedResume));
        Job deletedJob = job(21L, "Java后端开发实习生", "描述");
        deletedJob.setCompanyName("示例科技");
        deletedJob.setStatus(Job.STATUS_DELETED);
        when(jobMapper.selectList(any())).thenReturn(List.of(deletedJob));

        DashboardSummary summary = dashboardService.summary(USER_ID);

        assertEquals(2, summary.getRecentMatches().size());
        DashboardRecentMatch first = summary.getRecentMatches().get(0);
        // 固定 8 字段（无版本号），顺序与查询一致（最新在前）
        assertEquals(702L, first.getId());
        assertEquals(11L, first.getResumeId());
        assertEquals("Java后端简历", first.getResumeName());
        assertEquals(21L, first.getJobId());
        assertEquals("Java后端开发实习生", first.getJobName());
        assertEquals("示例科技", first.getCompanyName());
        assertEquals(91.5, first.getMatchScore());
        assertEquals(LocalDateTime.of(2026, 10, 12, 10, 0), first.getCreatedAt());
    }

    // ---------------------------------------------------------------- 匹配：能力差距

    @Test
    void summary_topGaps_missingFirstThenPartialLimitedToThree() {
        MatchRecord latest = matchRecord(703L, new BigDecimal("75.0"));
        latest.setMissingItems(List.of(
                item("缺 Docker", "requiredSkills"),
                item("缺 Redis", "preferredSkills"),
                item("缺 K8s", null)));
        latest.setPartialItems(List.of(item("项目经验偏浅", "projectRelevance")));
        when(matchRecordMapper.selectOne(any())).thenReturn(latest);
        stubEmptyMatchPage();

        List<DashboardGapItem> gaps = dashboardService.summary(USER_ID).getTopGaps();

        // missing 共 3 条已占满上限：partial 被截掉；dimension 为 null 时原样保留
        assertEquals(3, gaps.size());
        assertEquals("MISSING", gaps.get(0).getType());
        assertEquals("缺 Docker", gaps.get(0).getText());
        assertEquals("requiredSkills", gaps.get(0).getDimension());
        assertEquals("MISSING", gaps.get(2).getType());
        assertEquals("缺 K8s", gaps.get(2).getText());
        assertNull(gaps.get(2).getDimension());
    }

    @Test
    void summary_topGaps_partialFillsAfterMissing() {
        MatchRecord latest = matchRecord(704L, new BigDecimal("70.0"));
        latest.setMissingItems(List.of(item("缺 A", null), item("缺 B", null)));
        latest.setPartialItems(List.of(item("部分 A", null), item("部分 B", null)));
        when(matchRecordMapper.selectOne(any())).thenReturn(latest);
        stubEmptyMatchPage();

        List<DashboardGapItem> gaps = dashboardService.summary(USER_ID).getTopGaps();

        assertEquals(3, gaps.size());
        assertEquals("MISSING", gaps.get(0).getType());
        assertEquals("MISSING", gaps.get(1).getType());
        assertEquals("PARTIAL", gaps.get(2).getType());
        assertEquals("部分 A", gaps.get(2).getText());
    }

    @Test
    void summary_topGaps_nullListsAreSafe() {
        // 明细列为 null（历史数据 / 模型输出空）时不抛异常，返回空数组
        when(matchRecordMapper.selectOne(any())).thenReturn(matchRecord(705L, new BigDecimal("60.0")));
        stubEmptyMatchPage();

        assertTrue(dashboardService.summary(USER_ID).getTopGaps().isEmpty());
    }

    // ---------------------------------------------------------------- 面试计数

    @Test
    void summary_interviewCounts_totalOngoingCompleted() {
        // 三次 selectCount 依次为：总数（含 1 条已取消）、进行中、已完成
        when(interviewSessionMapper.selectCount(any())).thenReturn(3L, 1L, 1L);
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        // status = 2（已取消）计入总数、不计入两个细分（第一版无取消入口，仅口径防御）
        assertEquals(3, summary.getInterviewCount());
        assertEquals(1, summary.getInterviewOngoingCount());
        assertEquals(1, summary.getInterviewCompletedCount());
    }

    // ---------------------------------------------------------------- 最近任务（M13）

    @Test
    void summary_recentTasks_mergesFourKindsInTimeOrderAndCapsAtSix() {
        when(resumeMapper.selectList(any())).thenReturn(List.of(
                resume(11L, "简历A"), resume(12L, "简历B")));
        when(resumeAnalysisMapper.selectList(any())).thenReturn(List.of(
                resumeAnalysisAt(11L, "2026-10-14T10:00:05"),
                resumeAnalysisAt(12L, "2026-10-14T10:00:04")));
        when(jobMapper.selectList(any())).thenReturn(List.of(job(21L, "岗位A", "描述A")));
        when(jobAnalysisMapper.selectList(any())).thenReturn(List.of(
                jobAnalysisAt(21L, "2026-10-14T10:00:03")));
        when(resumeOptimizationMapper.selectList(any())).thenReturn(List.of(
                optimization(301L, 11L, 21L, "2026-10-14T10:00:02"),
                optimization(302L, 12L, 21L, "2026-10-14T10:00:01")));
        when(interviewSessionMapper.selectList(any())).thenReturn(List.of(
                interviewSession(801L, "面试一", "2026-10-14T10:00:07"),
                interviewSession(802L, "面试二", "2026-10-14T10:00:06")));
        stubEmptyMatchPage();

        List<DashboardRecentTask> tasks = dashboardService.summary(USER_ID).getRecentTasks();

        // 候选 7 条（2 简历分析 + 1 岗位分析 + 2 优化 + 2 面试），按时间倒序截断到 6
        assertEquals(6, tasks.size());
        assertEquals(List.of("INTERVIEW", "INTERVIEW", "RESUME_ANALYSIS", "RESUME_ANALYSIS",
                        "JOB_ANALYSIS", "OPTIMIZATION"),
                tasks.stream().map(DashboardRecentTask::getType).toList());
        assertEquals("面试一", tasks.get(0).getTitle());
        assertEquals(801L, tasks.get(0).getRefId());
        // 优化任务标题为「简历名 · 岗位名」，refId 为优化记录 id（301 在前、302 被截）
        assertEquals("简历A · 岗位A", tasks.get(5).getTitle());
        assertEquals(301L, tasks.get(5).getRefId());
    }

    @Test
    void summary_recentTasks_noActiveResume_shortCircuitsAnalysisQueries() {
        // 无有效简历（含全部已删的情形——wrapper 的 status 过滤由真库执行，见接口层脚本）：
        // 分析查询整体短路，任务线为空
        when(resumeMapper.selectList(any())).thenReturn(List.of());
        stubEmptyMatchPage();

        DashboardSummary summary = dashboardService.summary(USER_ID);

        assertTrue(summary.getRecentTasks().isEmpty());
        // 有效简历为空 → 不对分析表发 IN 查询（空集合先短路）
        verify(resumeAnalysisMapper, never()).selectList(any());
    }

    @Test
    void summary_recentTasks_optimizationTitlesKeepDeletedSourceNames() {
        // 优化记录本身无删除语义：源资源已删仍进任务线，名称不过滤 status 仍要显示
        Resume deletedResume = resume(11L, "已删简历");
        deletedResume.setStatus(Resume.STATUS_DELETED);
        Job deletedJob = job(21L, "已删岗位", "描述");
        deletedJob.setStatus(Job.STATUS_DELETED);
        when(resumeMapper.selectList(any())).thenReturn(List.of(deletedResume));
        when(jobMapper.selectList(any())).thenReturn(List.of(deletedJob));
        when(resumeOptimizationMapper.selectList(any())).thenReturn(List.of(
                optimization(301L, 11L, 21L, "2026-10-14T10:00:02")));
        stubEmptyMatchPage();

        List<DashboardRecentTask> tasks = dashboardService.summary(USER_ID).getRecentTasks();

        assertEquals(1, tasks.size());
        assertEquals("OPTIMIZATION", tasks.get(0).getType());
        assertEquals("已删简历 · 已删岗位", tasks.get(0).getTitle());
    }

    // ---------------------------------------------------------------- 辅助

    /** 最近匹配分页默认返回空页（Mockito 对 Page 默认返回 null，必须显式 stub） */
    private void stubEmptyMatchPage() {
        Page<MatchRecord> page = new Page<>(1, 5);
        page.setRecords(List.of());
        when(matchRecordMapper.selectPage(any(), any())).thenReturn(page);
    }

    private Resume resume(Long id, String name) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(USER_ID);
        resume.setName(name);
        resume.setStatus(Resume.STATUS_ACTIVE);
        return resume;
    }

    private ResumeAnalysis resumeAnalysis(Long resumeId) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setResumeId(resumeId);
        analysis.setStatus(ResumeAnalysis.STATUS_SUCCESS);
        // M13：任务线排序用（DB 中 created_at NOT NULL，mock 数据同样自洽）
        analysis.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        return analysis;
    }

    private Job job(Long id, String jobName, String description) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(USER_ID);
        job.setJobName(jobName);
        job.setCompanyName("示例科技");
        job.setDescription(description);
        job.setStatus(Job.STATUS_ACTIVE);
        return job;
    }

    private JobAnalysis jobAnalysis(Long jobId, int versionNo, String sourceHash) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setJobId(jobId);
        analysis.setVersionNo(versionNo);
        analysis.setSourceHash(sourceHash);
        analysis.setStatus(JobAnalysis.STATUS_SUCCESS);
        // M13：任务线排序用（DB 中 created_at NOT NULL，mock 数据同样自洽）
        analysis.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        return analysis;
    }

    private MatchRecord matchRecord(Long id, BigDecimal score) {
        MatchRecord record = new MatchRecord();
        record.setId(id);
        record.setUserId(USER_ID);
        record.setResumeId(11L);
        record.setJobId(21L);
        record.setMatchScore(score);
        record.setCreatedAt(LocalDateTime.of(2026, 10, 12, 9, 0));
        return record;
    }

    private MatchItem item(String text, String dimension) {
        MatchItem item = new MatchItem();
        item.setText(text);
        item.setDimension(dimension);
        return item;
    }

    private ResumeAnalysis resumeAnalysisAt(Long resumeId, String createdAt) {
        ResumeAnalysis analysis = resumeAnalysis(resumeId);
        analysis.setCreatedAt(LocalDateTime.parse(createdAt));
        return analysis;
    }

    private JobAnalysis jobAnalysisAt(Long jobId, String createdAt) {
        JobAnalysis analysis = jobAnalysis(jobId, 1, Hashing.sha256Hex("描述A"));
        analysis.setCreatedAt(LocalDateTime.parse(createdAt));
        return analysis;
    }

    private ResumeOptimization optimization(Long id, Long resumeId, Long jobId, String createdAt) {
        ResumeOptimization optimization = new ResumeOptimization();
        optimization.setId(id);
        optimization.setUserId(USER_ID);
        optimization.setResumeId(resumeId);
        optimization.setJobId(jobId);
        optimization.setCreatedAt(LocalDateTime.parse(createdAt));
        return optimization;
    }

    private InterviewSession interviewSession(Long id, String title, String createdAt) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setUserId(USER_ID);
        session.setTitle(title);
        session.setCreatedAt(LocalDateTime.parse(createdAt));
        return session;
    }

}
