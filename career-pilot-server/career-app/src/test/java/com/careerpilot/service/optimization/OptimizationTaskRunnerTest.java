package com.careerpilot.service.optimization;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.careerpilot.common.BusinessException;
import com.careerpilot.common.Hashing;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.JobMapper;
import com.careerpilot.mapper.ResumeMapper;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.MatchScoringResult;
import com.careerpilot.service.MatchScoringService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ai.ResumeOptimizationAiService;

/**
 * 任务执行体的安全边界：模型输出被拒时不得写成功终态（第三阶段，O-01/O-02）。
 */
@ExtendWith(MockitoExtension.class)
class OptimizationTaskRunnerTest {

    private static final Long OPTIMIZATION_ID = 301L;

    private static final Long RESUME_ID = 101L;

    private static final Long JOB_ID = 201L;

    private static final String DESCRIPTION = "岗位职责：参与核心业务开发\n任职要求：熟悉 Java";

    @Mock
    private ResumeOptimizationMapper optimizationMapper;

    @Mock
    private ResumeMapper resumeMapper;

    @Mock
    private JobMapper jobMapper;

    @Mock
    private ResumeAnalysisService resumeAnalysisService;

    @Mock
    private JobAnalysisService jobAnalysisService;

    @Mock
    private MatchService matchService;

    @Mock
    private MatchScoringService matchScoringService;

    @Mock
    private ResumeOptimizationAiService resumeOptimizationAiService;

    @Mock
    private OptimizationTaskStateService stateService;

    private OptimizationTaskRunner runner;

    @BeforeEach
    void setUp() {
        runner = new OptimizationTaskRunner(optimizationMapper, resumeMapper, jobMapper,
                resumeAnalysisService, jobAnalysisService, matchService, matchScoringService,
                resumeOptimizationAiService, stateService);
    }

    @Test
    void run_generateRejected_doesNotCompleteSuccess() {
        ResumeOptimization record = new ResumeOptimization();
        record.setId(OPTIMIZATION_ID);
        record.setResumeId(RESUME_ID);
        record.setJobId(JOB_ID);
        Resume resume = new Resume();
        resume.setId(RESUME_ID);
        resume.setContent("Java 后端简历");
        Job job = new Job();
        job.setId(JOB_ID);
        job.setDescription(DESCRIPTION);
        ResumeAnalysis resumeAnalysis = new ResumeAnalysis();
        resumeAnalysis.setId(21L);
        JobAnalysis jobAnalysis = new JobAnalysis();
        jobAnalysis.setId(11L);
        jobAnalysis.setSourceHash(Hashing.sha256Hex(DESCRIPTION));
        MatchDetail match = new MatchDetail();
        match.setId(501L);
        MatchScoringResult scoring = new MatchScoringResult(
                new BigDecimal("75.75"), new MatchDimensionScores("v1", List.of()), List.of());

        when(optimizationMapper.selectById(OPTIMIZATION_ID)).thenReturn(record);
        when(resumeMapper.selectById(RESUME_ID)).thenReturn(resume);
        when(jobMapper.selectById(JOB_ID)).thenReturn(job);
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis);
        when(stateService.updateStage(OPTIMIZATION_ID, "attempt-1",
                ResumeOptimization.STAGE_MATCHING)).thenReturn(true);
        when(stateService.updateStage(OPTIMIZATION_ID, "attempt-1",
                ResumeOptimization.STAGE_GENERATING)).thenReturn(true);
        when(matchService.create(resume, job, resumeAnalysis, jobAnalysis)).thenReturn(match);
        when(matchScoringService.score(jobAnalysis, resumeAnalysis)).thenReturn(scoring);
        when(resumeOptimizationAiService.generate(resume, jobAnalysis, resumeAnalysis, scoring))
                .thenThrow(BusinessException.aiInvalidOutput(
                        "生成的优化稿超过长度限制（8000 字符），未保存为有效优化结果"));

        assertThatThrownBy(() -> runner.run(OPTIMIZATION_ID, "attempt-1"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("超过长度限制");

        verify(stateService).updateStage(OPTIMIZATION_ID, "attempt-1",
                ResumeOptimization.STAGE_MATCHING);
        verify(stateService).updateStage(OPTIMIZATION_ID, "attempt-1",
                ResumeOptimization.STAGE_GENERATING);
        // 关键：被拒绝的输出不得进入成功落库路径
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }

    /** 心跳 CAS 失败（代次已失效）时必须立即停止，不得继续发起匹配/生成模型调用。 */
    @Test
    void run_stageRejected_stopsBeforeModelCalls() {
        ResumeOptimization record = new ResumeOptimization();
        record.setId(OPTIMIZATION_ID);
        record.setResumeId(RESUME_ID);
        record.setJobId(JOB_ID);
        Resume resume = new Resume();
        resume.setId(RESUME_ID);
        resume.setContent("Java 后端简历");
        Job job = new Job();
        job.setId(JOB_ID);
        job.setDescription(DESCRIPTION);
        ResumeAnalysis resumeAnalysis = new ResumeAnalysis();
        resumeAnalysis.setId(21L);
        JobAnalysis jobAnalysis = new JobAnalysis();
        jobAnalysis.setId(11L);
        jobAnalysis.setSourceHash(Hashing.sha256Hex(DESCRIPTION));

        when(optimizationMapper.selectById(OPTIMIZATION_ID)).thenReturn(record);
        when(resumeMapper.selectById(RESUME_ID)).thenReturn(resume);
        when(jobMapper.selectById(JOB_ID)).thenReturn(job);
        when(resumeAnalysisService.findLatestSuccess(RESUME_ID)).thenReturn(resumeAnalysis);
        when(jobAnalysisService.findLatestSuccess(JOB_ID)).thenReturn(jobAnalysis);
        when(stateService.updateStage(OPTIMIZATION_ID, "attempt-1",
                ResumeOptimization.STAGE_MATCHING)).thenReturn(false);

        runner.run(OPTIMIZATION_ID, "attempt-1");

        verify(matchService, never()).create(any(), any(), any(), any());
        verify(resumeOptimizationAiService, never()).generate(any(), any(), any(), any());
        verify(stateService, never()).complete(any(), any(), any(), any(), any(), any());
    }
}
