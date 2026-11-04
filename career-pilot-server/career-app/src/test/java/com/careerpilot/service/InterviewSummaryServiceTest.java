package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.entity.InterviewQuestion;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.Job;
import com.careerpilot.mapper.InterviewQuestionMapper;
import com.careerpilot.mapper.InterviewSessionMapper;
import com.careerpilot.service.ai.InterviewAiService;
import com.careerpilot.service.ai.InterviewSummaryFallback;

/**
 * 阶段 8：异步消费者与同步路径共用的总评生成逻辑。
 *
 * <p>覆盖三条不变量：**已生成 AI 总评不再重复调用模型**（消息幂等的最后一道闸）、
 * 模型失败写 {@code DEGRADED} 而非抛出、归属不符 404。
 */
class InterviewSummaryServiceTest {

    private InterviewSessionMapper sessionMapper;

    private InterviewQuestionMapper questionMapper;

    private JobService jobService;

    private InterviewAiService aiService;

    private InterviewTxService txService;

    private InterviewSummaryFallback fallback;

    private InterviewSummaryService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(InterviewSessionMapper.class);
        questionMapper = mock(InterviewQuestionMapper.class);
        jobService = mock(JobService.class);
        aiService = mock(InterviewAiService.class);
        txService = mock(InterviewTxService.class);
        fallback = mock(InterviewSummaryFallback.class);
        service = new InterviewSummaryService(
                sessionMapper, questionMapper, jobService, aiService, txService, fallback);
    }

    @Test
    void generateForSession_aiSummaryAlreadyWritten_skipsModel() {
        InterviewSession session = session(InterviewTxService.SUMMARY_SOURCE_AI);
        when(sessionMapper.selectById(10L)).thenReturn(session);

        String source = service.generateForSession(1L, 10L);

        assertEquals(InterviewTxService.SUMMARY_SOURCE_AI, source);
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void generateForSession_modelSuccess_writesAiSummary() {
        InterviewSession session = session(null);
        when(sessionMapper.selectById(10L)).thenReturn(session);
        when(questionMapper.selectList(any())).thenReturn(List.of(question()));
        when(jobService.getJobById(9L)).thenReturn(job());
        InterviewSummaryAiOutput output = summary("整体表现良好");
        when(aiService.summarize(any(), eq("MIXED"), anyList(), eq(new BigDecimal("85.00")), eq("key-1")))
                .thenReturn(new AiCallResult<>(output, "deepseek-flash", 100L, 1, 1, 2));

        String source = service.generateForSession(1L, 10L);

        assertEquals(InterviewTxService.SUMMARY_SOURCE_AI, source);
        verify(txService).saveSummary(10L, output, InterviewTxService.SUMMARY_SOURCE_AI);
    }

    @Test
    void generateForSession_modelFailure_writesDegradedSummary() {
        InterviewSession session = session(null);
        when(sessionMapper.selectById(10L)).thenReturn(session);
        when(questionMapper.selectList(any())).thenReturn(List.of(question()));
        when(jobService.getJobById(9L)).thenReturn(job());
        when(aiService.summarize(any(), any(), anyList(), any(), any()))
                .thenThrow(BusinessException.aiUnavailable("大模型服务暂时不可用"));
        InterviewSummaryAiOutput degraded = summary("系统汇总");
        when(fallback.build(anyList(), any())).thenReturn(degraded);

        String source = service.generateForSession(1L, 10L);

        assertEquals(InterviewTxService.SUMMARY_SOURCE_DEGRADED, source);
        verify(txService).saveSummary(10L, degraded, InterviewTxService.SUMMARY_SOURCE_DEGRADED);
    }

    @Test
    void generateForSession_otherUsersSession_404WithoutModelCall() {
        when(sessionMapper.selectById(10L)).thenReturn(session(null));

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
                () -> service.generateForSession(2L, 10L));

        assertEquals(40400, ex.getCode());
        verify(aiService, never()).summarize(any(), any(), anyList(), any(), any());
    }

    // ---------------------------------------------------------------- 夹具

    private static InterviewSession session(String summarySource) {
        InterviewSession session = new InterviewSession();
        session.setId(10L);
        session.setUserId(1L);
        session.setJobId(9L);
        session.setQuestionType("MIXED");
        session.setTotalScore(new BigDecimal("85.00"));
        session.setLlmProviderKey("key-1");
        session.setSummarySource(summarySource);
        return session;
    }

    private static InterviewQuestion question() {
        InterviewQuestion question = new InterviewQuestion();
        question.setScore(new BigDecimal("85.00"));
        return question;
    }

    private static Job job() {
        Job job = new Job();
        job.setId(9L);
        job.setJobName("Java 后端");
        job.setDescription("描述");
        return job;
    }

    private static InterviewSummaryAiOutput summary(String text) {
        InterviewSummaryAiOutput output = new InterviewSummaryAiOutput();
        output.setSummary(text);
        return output;
    }
}
