package com.careerpilot.agent.tools;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.careerpilot.common.Hashing;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 匹配工具（《Agent设计》§4.4）与 REST 的差异契约：**缺分析自动补齐**（REST 返回 400），
 * 其余规则共用同一 Service；分数只来自规则计算（AG-08）。
 */
class MatchAgentToolsTest {

    private ResumeService resumeService;

    private JobService jobService;

    private ResumeAnalysisService resumeAnalysisService;

    private JobAnalysisService jobAnalysisService;

    private MatchService matchService;

    private MatchAgentTools tools;

    @BeforeEach
    void setUp() {
        resumeService = mock(ResumeService.class);
        jobService = mock(JobService.class);
        resumeAnalysisService = mock(ResumeAnalysisService.class);
        jobAnalysisService = mock(JobAnalysisService.class);
        matchService = mock(MatchService.class);
        tools = new MatchAgentTools(resumeService, jobService, resumeAnalysisService,
                jobAnalysisService, matchService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "tester"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void matchResumeToJob_missingAnalyses_autoFillsBothThenMatches() {
        Resume resume = new Resume();
        resume.setId(12L);
        Job job = new Job();
        job.setId(34L);
        job.setJobName("Java 后端开发实习生");
        when(resumeService.getOwnedResume(7L, 12L)).thenReturn(resume);
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);

        ResumeAnalysis resumeAnalysis = new ResumeAnalysis();
        JobAnalysis jobAnalysis = new JobAnalysis();
        // 双方都缺分析：补齐后再次查询能拿到结果
        when(resumeAnalysisService.findLatestSuccess(12L)).thenReturn(null, resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(34L)).thenReturn(null, jobAnalysis);
        when(matchService.create(resume, job, resumeAnalysis, jobAnalysis))
                .thenReturn(matchDetail(82.4));

        String payload = tools.matchResumeToJob(12L, 34L);

        // 与 REST 的差异：工具内部自动补齐，而不是返回 400
        verify(resumeAnalysisService).analyze(resume);
        verify(jobAnalysisService).analyze(job);
        assertTrue(payload.contains("\"status\":\"SUCCESS\""), payload);
        assertTrue(payload.contains("82.4"), payload);
    }

    @Test
    void matchResumeToJob_staleJobAnalysis_regenerates() {
        Resume resume = new Resume();
        resume.setId(12L);
        Job job = new Job();
        job.setId(34L);
        job.setDescription("新版岗位描述");
        when(resumeService.getOwnedResume(7L, 12L)).thenReturn(resume);
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);

        ResumeAnalysis resumeAnalysis = new ResumeAnalysis();
        JobAnalysis staleAnalysis = new JobAnalysis();
        staleAnalysis.setSourceHash("旧描述的哈希");  // 与当前描述不一致 → isStale 为真
        JobAnalysis freshAnalysis = new JobAnalysis();
        when(resumeAnalysisService.findLatestSuccess(12L)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(34L)).thenReturn(staleAnalysis, freshAnalysis);
        when(matchService.create(resume, job, resumeAnalysis, freshAnalysis))
                .thenReturn(matchDetail(80.0));

        tools.matchResumeToJob(12L, 34L);

        // 过期 → 重算（analyze 内部按 source_hash 决定复用/生成）
        verify(jobAnalysisService).analyze(job);
        verify(matchService).create(resume, job, resumeAnalysis, freshAnalysis);
    }

    @Test
    void matchResumeToJob_freshAnalyses_doesNotReanalyze() {
        Resume resume = new Resume();
        resume.setId(12L);
        Job job = new Job();
        job.setId(34L);
        job.setDescription("岗位描述");
        when(resumeService.getOwnedResume(7L, 12L)).thenReturn(resume);
        when(jobService.getOwnedJob(7L, 34L)).thenReturn(job);

        ResumeAnalysis resumeAnalysis = new ResumeAnalysis();
        JobAnalysis jobAnalysis = new JobAnalysis();
        // sourceHash 与当前描述一致 → 非过期，不应触发重算
        jobAnalysis.setSourceHash(Hashing.sha256Hex(job.getDescription()));
        when(resumeAnalysisService.findLatestSuccess(12L)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(34L)).thenReturn(jobAnalysis);
        when(matchService.create(any(), any(), any(), any())).thenReturn(matchDetail(75.5));

        tools.matchResumeToJob(12L, 34L);

        verify(resumeAnalysisService, never()).analyze(any());
        verify(jobAnalysisService, never()).analyze(any());
    }

    private static MatchDetail matchDetail(double score) {
        MatchDetail detail = new MatchDetail();
        detail.setId(701L);
        detail.setResumeId(12L);
        detail.setResumeName("Java 后端简历");
        detail.setJobId(34L);
        detail.setJobName("Java 后端开发实习生");
        detail.setMatchScore(score);
        return detail;
    }

}
