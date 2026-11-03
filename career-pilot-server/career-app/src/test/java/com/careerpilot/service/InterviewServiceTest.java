package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.AnswerEvaluationResponse;
import com.careerpilot.dto.CategoryScore;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewDetail;
import com.careerpilot.dto.InterviewReport;
import com.careerpilot.dto.ai.GeneratedQuestion;
import com.careerpilot.dto.ai.InterviewEvaluationAiOutput;
import com.careerpilot.dto.ai.InterviewQuestionsAiOutput;
import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.entity.InterviewQuestion;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.InterviewQuestionMapper;
import com.careerpilot.mapper.InterviewSessionMapper;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.MatchRecordMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.ai.InterviewAiService;
import com.careerpilot.service.ai.InterviewSummaryFallback;

/**
 * 面试编排的护栏（《API接口设计》§7）：
 * 归属 404 先于模型调用、失败不写入、总评失败不阻断、报告"只读不惰性计算"。
 */
class InterviewServiceTest {

    private InterviewSessionMapper sessionMapper;

    private InterviewQuestionMapper questionMapper;

    private JobMapper jobMapper;

    private JobService jobService;

    private InterviewAiService aiService;

    private InterviewTxService txService;

    private InterviewSummaryFallback summaryFallback;

    private ResumeService resumeService;

    private ResumeOptimizationService resumeOptimizationService;

    private MatchRecordMapper matchRecordMapper;

    private ResumeMapper resumeMapper;

    private ResumeOptimizationMapper resumeOptimizationMapper;

    private InterviewService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(InterviewSessionMapper.class);
        questionMapper = mock(InterviewQuestionMapper.class);
        jobMapper = mock(JobMapper.class);
        jobService = mock(JobService.class);
        aiService = mock(InterviewAiService.class);
        txService = mock(InterviewTxService.class);
        summaryFallback = mock(InterviewSummaryFallback.class);
        resumeService = mock(ResumeService.class);
        resumeOptimizationService = mock(ResumeOptimizationService.class);
        matchRecordMapper = mock(MatchRecordMapper.class);
        resumeMapper = mock(ResumeMapper.class);
        resumeOptimizationMapper = mock(ResumeOptimizationMapper.class);
        service = new InterviewService(sessionMapper, questionMapper, jobMapper,
                mock(JobAnalysisMapper.class), jobService, aiService, txService,
                summaryFallback,
                resumeService, resumeOptimizationService, matchRecordMapper,
                resumeMapper, resumeOptimizationMapper);
        // M15：历史题去重查询的宽松桩（不走创建路径的用例不受影响）
        lenient().when(sessionMapper.selectList(any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 创建（§7.1）

    @Test
    void create_jobNotOwned_404AndModelNotCalled() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenThrow(BusinessException.notFound("岗位不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(1L, createRequest(9L, 3)));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void create_modelFailure_502AndNothingSaved() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(aiService.generate(any(), any(), eq("MIXED"), eq(3), isNull(), isNull(), anyList()))
                .thenThrow(BusinessException.aiUnavailable("大模型服务暂时不可用"));

        assertThrows(BusinessException.class, () -> service.create(1L, createRequest(9L, 3)));

        // 模型失败先于任何写入：「不产生空会话」由"根本未进入写路径"保证
        verifyNoInteractions(txService);
    }

    @Test
    void create_success_assemblesCountsAndQuestions() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(aiService.generate(any(), any(), eq("MIXED"), eq(3), isNull(), isNull(), anyList()))
                .thenReturn(new AiCallResult<>(questionsOutput(2), "test-model", 100L, 1, 1, 2));
        when(txService.saveNewSession(any(), anyList())).thenAnswer(invocation -> {
            InterviewSession session = invocation.getArgument(0);
            session.setId(99L);
            return List.of(question(1L, "题目一", null, null), question(2L, "题目二", null, null));
        });

        InterviewCreateResponse response = service.create(1L, createRequest(9L, 3));

        assertEquals(99L, response.getId());
        assertEquals(3, response.getQuestionCount(), "questionCount 是计划数（落库值）");
        assertEquals(2, response.getGeneratedCount(), "generatedCount 是实际题数（行数）");
        assertEquals(2, response.getQuestions().size());
        assertEquals(InterviewSession.STATUS_ONGOING, response.getStatus());
    }

    // ---------------------------------------------------------------- 创建·来源与出题上下文（M12）

    @Test
    void create_withOptimization_derivesSourceAndContext() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(resumeOptimizationService.getOwned(eq(1L), eq(301L)))
                .thenReturn(optimization(301L, 101L, 9L, 201L));
        when(matchRecordMapper.selectById(201L)).thenReturn(matchRecord(201L));
        stubGenerateSuccess();

        InterviewCreateRequest request = createRequest(9L, 3);
        request.setOptimizationId(301L);
        service.create(1L, request);

        // 来源三元组由服务端从优化推导：resumeId 取自优化记录，不接受前端组合
        ArgumentCaptor<InterviewSession> sessionCaptor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(txService).saveNewSession(sessionCaptor.capture(), anyList());
        assertEquals(101L, sessionCaptor.getValue().getResumeId());
        assertEquals(301L, sessionCaptor.getValue().getOptimizationId());

        // 出题上下文：简历块取优化稿当前稿（editedContent 优先），差距块取该优化的匹配记录
        ArgumentCaptor<String> resumeText = ArgumentCaptor.forClass(String.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<MatchItem>> gaps = ArgumentCaptor.forClass(List.class);
        verify(aiService).generate(any(), any(), eq("MIXED"), eq(3), resumeText.capture(), gaps.capture(), anyList());
        assertEquals("优化后的简历内容", resumeText.getValue());
        assertEquals(2, gaps.getValue().size(), "missing 在前、partial 在后，各取前 5");
        assertEquals("简历未体现 Redis 使用经验", gaps.getValue().get(0).getText());
    }

    @Test
    void create_optimizationJobMismatch_400AndModelNotCalled() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(resumeOptimizationService.getOwned(eq(1L), eq(301L)))
                .thenReturn(optimization(301L, 101L, 7L, 201L));

        InterviewCreateRequest request = createRequest(9L, 3);
        request.setOptimizationId(301L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, request));

        assertEquals(40000, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void create_optimizationWithMismatchedResume_400() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(resumeOptimizationService.getOwned(eq(1L), eq(301L)))
                .thenReturn(optimization(301L, 101L, 9L, 201L));

        InterviewCreateRequest request = createRequest(9L, 3);
        request.setOptimizationId(301L);
        request.setResumeId(999L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, request));

        assertEquals(40000, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void create_optimizationNotOwned_404AndModelNotCalled() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(resumeOptimizationService.getOwned(eq(1L), eq(301L)))
                .thenThrow(BusinessException.notFound("优化记录不存在"));

        InterviewCreateRequest request = createRequest(9L, 3);
        request.setOptimizationId(301L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, request));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void create_withResumeOnly_usesTextAndLatestMatchGaps() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        when(resumeService.getOwnedResume(eq(1L), eq(101L))).thenReturn(resume(101L));
        when(matchRecordMapper.selectOne(any())).thenReturn(matchRecord(201L));
        stubGenerateSuccess();

        InterviewCreateRequest request = createRequest(9L, 3);
        request.setResumeId(101L);
        service.create(1L, request);

        ArgumentCaptor<InterviewSession> sessionCaptor = ArgumentCaptor.forClass(InterviewSession.class);
        verify(txService).saveNewSession(sessionCaptor.capture(), anyList());
        assertEquals(101L, sessionCaptor.getValue().getResumeId());
        assertNull(sessionCaptor.getValue().getOptimizationId());

        verify(aiService).generate(any(), any(), eq("MIXED"), eq(3),
                eq("简历正文：熟悉 Java 与 Spring"), any(), anyList());
    }

    @Test
    void create_withoutSource_generatesWithNullContext() {
        when(jobService.getOwnedJob(eq(1L), eq(9L))).thenReturn(job());
        stubGenerateSuccess();

        service.create(1L, createRequest(9L, 3));

        verify(aiService).generate(any(), any(), eq("MIXED"), eq(3), isNull(), isNull(), anyList());
        // 无来源时不查任何上下文资源（与历史行为等价）
        verifyNoInteractions(resumeService, resumeOptimizationService, matchRecordMapper);
    }

    /** 出题成功 + 会话落库的最小桩（M12 创建测试共用） */
    private void stubGenerateSuccess() {
        when(aiService.generate(any(), any(), eq("MIXED"), eq(3), any(), any(), anyList()))
                .thenReturn(new AiCallResult<>(questionsOutput(1), "test-model", 100L, 1, 1, 2));
        when(txService.saveNewSession(any(), anyList())).thenAnswer(invocation -> {
            InterviewSession session = invocation.getArgument(0);
            session.setId(99L);
            return List.of(question(1L, "题目一", null, null));
        });
    }

    // ---------------------------------------------------------------- 提交回答（§7.2）

    @Test
    void submitAnswer_sessionNotOwned_404AndModelNotCalled() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitAnswer(1L, 10L, 100L, "回答"));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void submitAnswer_questionNotInSession_404() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_ONGOING));
        when(questionMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitAnswer(1L, 10L, 100L, "回答"));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void submitAnswer_cancelledSession_400() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_CANCELLED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.submitAnswer(1L, 10L, 100L, "回答"));

        assertEquals(40000, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void submitAnswer_evaluationFailure_502AndNothingWritten() {
        stubOwnedSessionAndQuestion();
        when(aiService.evaluate(any(), any(), eq("我的回答")))
                .thenThrow(BusinessException.aiInvalidOutput("大模型输出无法解析"));

        assertThrows(BusinessException.class, () -> service.submitAnswer(1L, 10L, 100L, "我的回答"));

        // 评价失败整体不入库：写路径根本未被调用
        verifyNoInteractions(txService);
    }

    @Test
    void submitAnswer_partialAnswer_noSummary() {
        stubOwnedSessionAndQuestion();
        when(aiService.evaluate(any(), any(), any()))
                .thenReturn(new AiCallResult<>(evaluation(), "test-model", 100L, 1, 1, 2));
        when(txService.applyAnswerAndRecompute(eq(1L), eq(10L), eq(100L), any(), any()))
                .thenReturn(new InterviewTxService.AnswerOutcome(false, null, List.of()));

        AnswerEvaluationResponse response = service.submitAnswer(1L, 10L, 100L, "我的回答");

        assertEquals(new BigDecimal("78.00"), response.getScore());
        verify(aiService, never()).summarize(any(), any(), anyList(), any());
    }

    @Test
    void submitAnswer_completed_generatesSummary() {
        stubOwnedSessionAndQuestion();
        when(aiService.evaluate(any(), any(), any()))
                .thenReturn(new AiCallResult<>(evaluation(), "test-model", 100L, 1, 1, 2));
        List<InterviewQuestion> answered = List.of(
                question(1L, "题目一", new BigDecimal("80.00"), "回答一"),
                question(2L, "题目二", new BigDecimal("90.00"), "回答二"));
        when(txService.applyAnswerAndRecompute(eq(1L), eq(10L), eq(100L), any(), any()))
                .thenReturn(new InterviewTxService.AnswerOutcome(true, new BigDecimal("85.00"), answered));
        InterviewSummaryAiOutput summary = new InterviewSummaryAiOutput();
        summary.setSummary("整体表现良好");
        when(aiService.summarize(any(), eq("MIXED"), eq(answered), eq(new BigDecimal("85.00"))))
                .thenReturn(new AiCallResult<>(summary, "test-model", 100L, 1, 1, 2));

        service.submitAnswer(1L, 10L, 100L, "我的回答");

        verify(txService).saveSummary(eq(10L), eq(summary),
                eq(InterviewTxService.SUMMARY_SOURCE_AI));
    }

    @Test
    void submitAnswer_summaryFailureDoesNotBreakSubmission() {
        stubOwnedSessionAndQuestion();
        when(aiService.evaluate(any(), any(), any()))
                .thenReturn(new AiCallResult<>(evaluation(), "test-model", 100L, 1, 1, 2));
        when(txService.applyAnswerAndRecompute(eq(1L), eq(10L), eq(100L), any(), any()))
                .thenReturn(new InterviewTxService.AnswerOutcome(true, new BigDecimal("85.00"), List.of()));
        when(aiService.summarize(any(), any(), anyList(), any()))
                .thenThrow(BusinessException.aiUnavailable("大模型服务暂时不可用"));
        // M15：失败时写入确定性降级总评（DEGRADED）——"降级"可见，不再静默缺失
        InterviewSummaryAiOutput degraded = new InterviewSummaryAiOutput();
        degraded.setSummary("本次总评由系统汇总生成（测试）");
        when(summaryFallback.build(anyList(), any())).thenReturn(degraded);

        AnswerEvaluationResponse response = service.submitAnswer(1L, 10L, 100L, "我的回答");

        assertNotNull(response, "总评生成失败不得使整个提交失败（回答/评价/总分已落库）");
        verify(txService).saveSummary(eq(10L), eq(degraded),
                eq(InterviewTxService.SUMMARY_SOURCE_DEGRADED));
    }

    // ---------------------------------------------------------------- 报告（§7.4）

    @Test
    void report_returnsWrittenTotalScoreWithoutRecomputing() {
        InterviewSession session = session(10L, InterviewSession.STATUS_COMPLETED);
        session.setTotalScore(new BigDecimal("42.00"));   // 库中值与题目平均（85）不同
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1L, "题目一", new BigDecimal("80.00"), "回答一"),
                question(2L, "题目二", new BigDecimal("90.00"), "回答二")));

        InterviewReport report = service.report(1L, 10L);

        assertEquals(new BigDecimal("42.00"), report.getTotalScore(),
                "报告接口只读已写入的值，绝不重算（R21）");
    }

    @Test
    void report_incomplete_returnsNullTotalsAndPartialCategories() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_ONGOING));
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1L, "题目一", new BigDecimal("80.00"), "回答一"),
                question(2L, "题目二", null, null)));

        InterviewReport report = service.report(1L, 10L);

        assertFalse(report.isCompleted());
        assertEquals(1, report.getAnsweredCount());
        assertNull(report.getTotalScore());
        assertNull(report.getOverallSummary());
        assertEquals(1, report.getCategoryScores().size(), "分类得分仅统计已作答题目");
        assertFalse(report.getQuestions().get(1).isAnswered());
    }

    @Test
    void report_categoryScoresGroupByCategoryWithNullGroup() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_COMPLETED));
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(1L, "题一", new BigDecimal("80.00"), "回答一", "SPRING"),
                question(2L, "题二", new BigDecimal("90.00"), "回答二", "SPRING"),
                question(3L, "题三", new BigDecimal("70.00"), "回答三", null)));

        InterviewReport report = service.report(1L, 10L);

        assertEquals(2, report.getCategoryScores().size());
        CategoryScore spring = report.getCategoryScores().get(0);
        assertEquals("SPRING", spring.getCategory());
        assertEquals(new BigDecimal("85.00"), spring.getAverageScore());
        assertEquals(2, spring.getQuestionCount());
        CategoryScore unclassified = report.getCategoryScores().get(1);
        assertNull(unclassified.getCategory(), "未给出类别的题目归入 null 组（与总分口径一致）");
        assertEquals(new BigDecimal("70.00"), unclassified.getAverageScore());
    }

    // ---------------------------------------------------------------- 详情与列表（§7.3）

    @Test
    void detail_ordersQuestionsAndKeepsNullEvaluationFields() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_ONGOING));
        when(questionMapper.selectList(any())).thenReturn(List.of(
                question(2L, "题目二", null, null),
                question(1L, "题目一", new BigDecimal("80.00"), "回答一")));

        InterviewDetail detail = service.detail(1L, 10L);

        assertEquals(2, detail.getGeneratedCount());
        assertEquals(1, detail.getAnsweredCount());
        assertNull(detail.getQuestions().get(0).getScore(), "未作答题目的评价字段均为 null");
        // 题目顺序由查询（sortOrder 升序）决定，装配不改序
        assertEquals(2L, detail.getQuestions().get(0).getId());
    }

    @Test
    void detail_withSource_fillsResumeNameAndOptimizationVersion() {
        InterviewSession session = session(10L, InterviewSession.STATUS_ONGOING);
        session.setResumeId(101L);
        session.setOptimizationId(301L);
        when(sessionMapper.selectOne(any())).thenReturn(session);
        when(questionMapper.selectList(any())).thenReturn(List.of());
        when(resumeMapper.selectById(101L)).thenReturn(resume(101L));
        ResumeOptimization optimization = optimization(301L, 101L, 9L, 201L);
        optimization.setVersionNo(2);
        when(resumeOptimizationMapper.selectById(301L)).thenReturn(optimization);

        InterviewDetail detail = service.detail(1L, 10L);

        assertEquals("张三-Java后端.pdf", detail.getResumeName());
        assertEquals(2, detail.getOptimizationVersionNo(), "来源展示：优化版本号（M12）");
    }

    @Test
    void list_emptyPageDoesNotQueryJobs() {
        Page<InterviewSession> page = new Page<>(1, 10);
        page.setRecords(List.of());
        page.setTotal(0);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<?> result = service.list(1L, new PageQuery());

        assertEquals(0, result.getTotal());
        assertTrue(result.getRecords().isEmpty());
        // 空页不发 IN 查询（无 N+1、无无谓查询）
        verify(jobMapper, never()).selectList(any());
        verify(questionMapper, never()).selectList(any());
    }

    // ---------------------------------------------------------------- 辅助

    private void stubOwnedSessionAndQuestion() {
        when(sessionMapper.selectOne(any())).thenReturn(session(10L, InterviewSession.STATUS_ONGOING));
        when(questionMapper.selectOne(any()))
                .thenReturn(question(100L, "请解释 Spring 的依赖注入", null, null, "SPRING"));
        when(jobMapper.selectById(9L)).thenReturn(job());
    }

    private static InterviewCreateRequest createRequest(Long jobId, int count) {
        InterviewCreateRequest request = new InterviewCreateRequest();
        request.setJobId(jobId);
        request.setTitle("Java 后端开发实习生模拟面试");
        request.setQuestionType("MIXED");
        request.setQuestionCount(count);
        return request;
    }

    private static Resume resume(Long id) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setName("张三-Java后端.pdf");
        resume.setContent("简历正文：熟悉 Java 与 Spring");
        return resume;
    }

    private static ResumeOptimization optimization(Long id, Long resumeId, Long jobId,
                                                   Long matchRecordId) {
        ResumeOptimization optimization = new ResumeOptimization();
        optimization.setId(id);
        optimization.setResumeId(resumeId);
        optimization.setJobId(jobId);
        optimization.setMatchRecordId(matchRecordId);
        optimization.setOptimizedContent("模型原稿内容");
        optimization.setEditedContent("优化后的简历内容");
        // M16：面试来源校验要求优化已成功完成（异步化后存在 PENDING/PROCESSING/FAILED 态）
        optimization.setStatus(ResumeOptimization.STATUS_SUCCESS);
        return optimization;
    }

    private static MatchRecord matchRecord(Long id) {
        MatchRecord match = new MatchRecord();
        match.setId(id);
        MatchItem missing = new MatchItem();
        missing.setText("简历未体现 Redis 使用经验");
        MatchItem partial = new MatchItem();
        partial.setText("Spring 使用经验偏少");
        match.setMissingItems(List.of(missing));
        match.setPartialItems(List.of(partial));
        return match;
    }

    private static InterviewQuestionsAiOutput questionsOutput(int count) {
        InterviewQuestionsAiOutput output = new InterviewQuestionsAiOutput();
        List<GeneratedQuestion> items = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            GeneratedQuestion item = new GeneratedQuestion();
            item.setQuestion("题目" + i);
            item.setQuestionCategory("JAVA");
            items.add(item);
        }
        output.setQuestions(items);
        return output;
    }

    private static InterviewEvaluationAiOutput evaluation() {
        InterviewEvaluationAiOutput output = new InterviewEvaluationAiOutput();
        output.setScore(new BigDecimal("78.00"));
        output.setAnalysis("点评");
        output.setReferenceAnswer("参考");
        output.setSuggestions(List.of("建议"));
        return output;
    }

    private static Job job() {
        Job job = new Job();
        job.setId(9L);
        job.setUserId(1L);
        job.setJobName("Java 后端开发实习生");
        job.setDescription("熟悉 Java 与 Spring Boot");
        return job;
    }

    private static InterviewSession session(Long id, int status) {
        InterviewSession session = new InterviewSession();
        session.setId(id);
        session.setUserId(1L);
        session.setJobId(9L);
        session.setTitle("模拟面试");
        session.setQuestionType("MIXED");
        session.setQuestionCount(3);
        session.setStatus(status);
        return session;
    }

    private static InterviewQuestion question(Long id, String text, BigDecimal score, String answer) {
        return question(id, text, score, answer, "JAVA");
    }

    private static InterviewQuestion question(Long id, String text, BigDecimal score, String answer,
                                              String category) {
        InterviewQuestion question = new InterviewQuestion();
        question.setId(id);
        question.setSessionId(10L);
        question.setQuestion(text);
        question.setQuestionCategory(category);
        question.setScore(score);
        question.setAnswer(answer);
        return question;
    }

}
