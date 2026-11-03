package com.careerpilot.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewQuestionItem;
import com.careerpilot.entity.Job;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.InterviewService;
import com.careerpilot.service.JobService;

/**
 * 面试出题工具（《Agent设计》§4.5、R27）：标题规则「{岗位名}模拟面试」截断 100、
 * 入参白名单与默认值、非法入参返回失败信封且**不创建会话**。
 */
class InterviewAgentToolsTest {

    private JobService jobService;

    private InterviewService interviewService;

    private InterviewAgentTools tools;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        interviewService = mock(InterviewService.class);
        tools = new InterviewAgentTools(jobService, interviewService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "tester"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void generateInterviewQuestions_defaultsToMixedAndFive_andBuildsTitleFromJobName() {
        Job job = job(34L, "Java 后端开发实习生");
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);
        when(interviewService.create(eq(7L), any())).thenReturn(response());

        String payload = tools.generateInterviewQuestions(34L, null, null);

        InterviewCreateRequest request = captureRequest();
        assertEquals("MIXED", request.getQuestionType());
        assertEquals(5, request.getQuestionCount());
        assertEquals("Java 后端开发实习生模拟面试", request.getTitle());
        assertEquals(34L, request.getJobId());
        assertTrue(payload.contains("\"status\":\"SUCCESS\""), payload);
        assertTrue(payload.contains("\"sessionId\":88"), payload);
    }

    @Test
    void generateInterviewQuestions_longJobName_titleCappedAt100() {
        Job job = job(34L, "超长岗位名".repeat(30));
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);
        when(interviewService.create(eq(7L), any())).thenReturn(response());

        tools.generateInterviewQuestions(34L, "JAVA", 3);

        InterviewCreateRequest request = captureRequest();
        assertEquals(100, request.getTitle().length());
        assertEquals("JAVA", request.getQuestionType());
        assertEquals(3, request.getQuestionCount());
    }

    @Test
    void generateInterviewQuestions_invalidType_returnsFailedEnvelopeAndCreatesNothing() {
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job(34L, "Java 后端开发实习生"));

        String payload = tools.generateInterviewQuestions(34L, "GO", 3);

        assertTrue(payload.contains("\"status\":\"FAILED\""), payload);
        assertTrue(payload.contains("INVALID_ARGUMENT"), payload);
        verifyNoInteractions(interviewService);
    }

    @Test
    void generateInterviewQuestions_countOutOfRange_returnsFailedEnvelopeAndCreatesNothing() {
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job(34L, "Java 后端开发实习生"));

        String payload = tools.generateInterviewQuestions(34L, "MIXED", 21);

        assertTrue(payload.contains("\"status\":\"FAILED\""), payload);
        verifyNoInteractions(interviewService);
    }

    // ---------------------------------------------------------------- 工具

    private InterviewCreateRequest captureRequest() {
        ArgumentCaptor<InterviewCreateRequest> captor =
                ArgumentCaptor.forClass(InterviewCreateRequest.class);
        verify(interviewService).create(eq(7L), captor.capture());
        return captor.getValue();
    }

    private static Job job(Long id, String name) {
        Job job = new Job();
        job.setId(id);
        job.setJobName(name);
        return job;
    }

    private static InterviewCreateResponse response() {
        InterviewQuestionItem item = new InterviewQuestionItem();
        item.setId(1L);
        item.setQuestion("请介绍 Spring Bean 的生命周期");
        item.setQuestionCategory("SPRING");
        item.setSortOrder(1);

        InterviewCreateResponse response = new InterviewCreateResponse();
        response.setId(88L);
        response.setTitle("Java 后端开发实习生模拟面试");
        response.setQuestionType("JAVA");
        response.setQuestionCount(3);
        response.setGeneratedCount(1);
        response.setStatus(0);
        response.setQuestions(List.of(item));
        return response;
    }

}
