package com.careerpilot.agent.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.entity.Job;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;

/**
 * 岗位工具（《Agent设计》§4.3）：归属 404、成功投影、复用 JobAnalysisService 的分析规则。
 */
class JobAgentToolsTest {

    private JobService jobService;

    private JobAnalysisService analysisService;

    private JobAgentTools tools;

    @BeforeEach
    void setUp() {
        jobService = mock(JobService.class);
        analysisService = mock(JobAnalysisService.class);
        tools = new JobAgentTools(jobService, analysisService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "tester"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analyzeJob_notOwned_throwsNotFound() {
        when(jobService.getOwnedJob(7L, 99L)).thenThrow(BusinessException.notFound("岗位不存在"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tools.analyzeJob(99L));

        assertEquals(40400, ex.getCode());
    }

    @Test
    void analyzeJob_success_returnsEnvelopeWithRequirements() {
        Job job = job(34L, "Java 后端开发实习生", "示例科技");
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);
        when(analysisService.analyze(job)).thenReturn(detail());

        String payload = tools.analyzeJob(34L);

        assertTrue(payload.contains("\"status\":\"SUCCESS\""), payload);
        assertTrue(payload.contains("\"jobId\":34"), payload);
        assertTrue(payload.contains("Java 后端开发实习生"), payload);
        assertTrue(payload.contains("Spring Boot"), payload);
        assertTrue(payload.contains("本科及以上"), payload);
    }

    private static Job job(Long id, String name, String company) {
        Job job = new Job();
        job.setId(id);
        job.setUserId(7L);
        job.setJobName(name);
        job.setCompanyName(company);
        job.setDescription("（岗位描述，不应出现在工具结果中）");
        return job;
    }

    private static JobAnalysisDetail detail() {
        JobAnalysisDetail detail = new JobAnalysisDetail();
        detail.setJobId(34L);
        detail.setVersionNo(1);
        detail.setSummary("面向应届生的后端开发岗位");
        detail.setResponsibilities(List.of("参与核心业务系统开发"));
        detail.setRequiredSkills(List.of("Java", "Spring Boot", "MySQL"));
        detail.setPreferredSkills(List.of("Docker"));
        detail.setTechnologyStack(List.of("Java", "Spring Boot"));
        detail.setEducationRequirement("本科及以上");
        detail.setExperienceRequirement("不限");
        return detail;
    }

}
