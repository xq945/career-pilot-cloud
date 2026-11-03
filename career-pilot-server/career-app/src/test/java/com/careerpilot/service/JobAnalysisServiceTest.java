package com.careerpilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.dto.ai.JobAnalysisAiOutput;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.mapper.JobAnalysisMapper;
import com.careerpilot.service.ai.JobAiService;

/**
 * 岗位分析业务编排的单测（《测试与验收方案》1.1：分析版本与落库）。
 *
 * 在 M4 简历分析同款用例之外，本类的增量是岗位特有的两类场景：
 * - **版本递增 v1→v2**：描述变化后生成新版本（2026-10-13 起描述未变同样生成新版本）；
 * - **重试耗尽的兜底必须同源**：M4 的无条件兜底依赖"简历不可变"，岗位不成立——
 *   见 {@link #analyze_retryExhausted_differentSource_failsInsteadOfReturningStaleResult}。
 */
@ExtendWith(MockitoExtension.class)
class JobAnalysisServiceTest {

    private static final String DESCRIPTION = "岗位职责：参与核心业务系统开发\n任职要求：熟悉 Java、Spring Boot";

    @Mock
    private JobService jobService;

    @Mock
    private JobAiService jobAiService;

    @Mock
    private JobAnalysisMapper jobAnalysisMapper;

    private JobAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new JobAnalysisService(jobService, jobAiService, jobAnalysisMapper);
    }

    // ---------------------------------------------------------------- 前置校验

    @Test
    void analyze_blankDescription_throwsBadRequestWithoutTouchingModelOrDb() {
        Job job = job("   ");

        assertThatThrownBy(() -> service.analyze(job))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
        verifyNoInteractions(jobAiService, jobAnalysisMapper);
    }

    // ---------------------------------------------------------------- 每次重新生成（2026-10-13 语义变更）

    @Test
    void analyze_descriptionUnchanged_stillGeneratesNewVersion() {
        Job job = job(DESCRIPTION);
        // 库里已有同源分析（v1），但「重新分析」不复用：仍调用模型并生成新版本
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(null, 5L, 1, null));
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("重新分析结果"));
        when(jobAnalysisMapper.insert(any(JobAnalysis.class))).thenAnswer(invocation -> {
            JobAnalysis inserted = invocation.getArgument(0);
            inserted.setId(41L);
            return 1;
        });

        JobAnalysisDetail detail = service.analyze(job);

        assertThat(detail.getAnalysisId()).isEqualTo(41L);
        assertThat(detail.getVersionNo()).isEqualTo(2);
        assertThat(detail.getSummary()).isEqualTo("重新分析结果");
        // 描述未变化时模型仍被调用（与旧语义相反：《开发顺序》M5 验收标准已随拍板修订）
        verify(jobAiService).analyze(DESCRIPTION);
    }

    // ---------------------------------------------------------------- 新版本

    @Test
    void analyze_changedDescription_generatesNextVersion() {
        Job job = job(DESCRIPTION);
        // 唯一一次 selectOne 是 nextVersionNo（已有 v1 → 新版本 v2）
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(null, 5L, 1, null));
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("新版本结果"));
        when(jobAnalysisMapper.insert(any(JobAnalysis.class))).thenAnswer(invocation -> {
            JobAnalysis inserted = invocation.getArgument(0);
            inserted.setId(20L);
            return 1;
        });

        JobAnalysisDetail detail = service.analyze(job);

        assertThat(detail.getAnalysisId()).isEqualTo(20L);
        // 《开发顺序》M5 验收标准 2：描述变化 → 生成新版本
        assertThat(detail.getVersionNo()).isEqualTo(2);
        assertThat(detail.getSummary()).isEqualTo("新版本结果");
        verify(jobAiService).analyze(DESCRIPTION);
    }

    @Test
    void analyze_noHistory_startsFromVersionOne() {
        Job job = job(DESCRIPTION);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(null);
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("首次分析"));
        when(jobAnalysisMapper.insert(any(JobAnalysis.class))).thenAnswer(invocation -> {
            JobAnalysis inserted = invocation.getArgument(0);
            inserted.setId(30L);
            return 1;
        });

        JobAnalysisDetail detail = service.analyze(job);

        assertThat(detail.getVersionNo()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 并发兜底

    @Test
    void analyze_versionConflict_retriesAndSucceeds() {
        Job job = job(DESCRIPTION);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(null);
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("结果"));
        doAnswer(invocation -> {
            throw new DuplicateKeyException("uk_job_analysis_version");
        }).doAnswer(invocation -> {
            JobAnalysis inserted = invocation.getArgument(0);
            inserted.setId(40L);
            return 1;
        }).when(jobAnalysisMapper).insert(any(JobAnalysis.class));

        JobAnalysisDetail detail = service.analyze(job);

        assertThat(detail.getAnalysisId()).isEqualTo(40L);
        // 版本冲突重试期间不重复调用模型（不重复计费）
        verify(jobAiService).analyze(DESCRIPTION);
    }

    // ---------------------------------------------------------------- 重试耗尽的兜底（岗位特有）

    @Test
    void analyze_retryExhausted_sameSourceFallback_returnsExistingResult() {
        Job job = job(DESCRIPTION);
        // selectOne 调用序：①②③ nextVersionNo ×3 ④ 兜底查询（同源，并发请求刚落库）
        when(jobAnalysisMapper.selectOne(any()))
                .thenReturn(version(1)).thenReturn(version(1)).thenReturn(version(1))
                .thenReturn(analysis(99L, 5L, 1, sha256(DESCRIPTION)));
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("并发结果"));
        doAnswer(invocation -> {
            throw new DuplicateKeyException("uk_job_analysis_version");
        }).when(jobAnalysisMapper).insert(any(JobAnalysis.class));

        JobAnalysisDetail detail = service.analyze(job);

        // 兜底记录与本次提交的描述同源 → 可以安全返回（M4 同款行为）
        assertThat(detail.getAnalysisId()).isEqualTo(99L);
    }

    @Test
    void analyze_retryExhausted_differentSource_failsInsteadOfReturningStaleResult() {
        Job job = job(DESCRIPTION);
        // 兜底查询返回的是**异源**记录（基于旧版描述的分析）：岗位场景下不得当作本次结果返回
        when(jobAnalysisMapper.selectOne(any()))
                .thenReturn(version(1)).thenReturn(version(1)).thenReturn(version(1))
                .thenReturn(analysis(99L, 5L, 1, sha256("旧版岗位描述")));
        when(jobAiService.analyze(DESCRIPTION)).thenReturn(aiResult("本次结果"));
        doAnswer(invocation -> {
            throw new DuplicateKeyException("uk_job_analysis_version");
        }).when(jobAnalysisMapper).insert(any(JobAnalysis.class));

        assertThatThrownBy(() -> service.analyze(job))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(50000));
    }

    // ---------------------------------------------------------------- 失败不落库

    @Test
    void analyze_modelFailure_propagatesAndWritesNothing() {
        Job job = job(DESCRIPTION);
        // 模型先于 nextVersionNo（selectOne）抛出，无需打桩版本查询
        when(jobAiService.analyze(DESCRIPTION)).thenThrow(BusinessException.aiUnavailable("模型不可用"));

        assertThatThrownBy(() -> service.analyze(job))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(50200));
        verify(jobAnalysisMapper, never()).insert(any(JobAnalysis.class));
    }

    // ---------------------------------------------------------------- 5.7 查询（含 stale 三态）

    @Test
    void getLatest_withoutAnalysis_returnsNotAnalyzedAndNotStale() {
        Job job = job(DESCRIPTION);
        when(jobService.getOwnedJob(1L, 5L)).thenReturn(job);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(null);

        JobAnalysisView view = service.getLatest(1L, 5L);

        assertThat(view.isAnalyzed()).isFalse();
        assertThat(view.isStale()).isFalse();
        assertThat(view.getAnalysis()).isNull();
    }

    @Test
    void getLatest_freshAnalysis_isAnalyzedAndNotStale() {
        Job job = job(DESCRIPTION);
        when(jobService.getOwnedJob(1L, 5L)).thenReturn(job);
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 5L, 1, sha256(DESCRIPTION)));

        JobAnalysisView view = service.getLatest(1L, 5L);

        assertThat(view.isAnalyzed()).isTrue();
        assertThat(view.isStale()).isFalse();
        assertThat(view.getAnalysis().getAnalysisId()).isEqualTo(10L);
    }

    @Test
    void getLatest_outdatedAnalysis_isAnalyzedAndStale() {
        Job job = job(DESCRIPTION);
        when(jobService.getOwnedJob(1L, 5L)).thenReturn(job);
        // 描述在分析后被修改过：source_hash 与当前描述不一致
        when(jobAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 5L, 1, sha256("旧版岗位描述")));

        JobAnalysisView view = service.getLatest(1L, 5L);

        assertThat(view.isAnalyzed()).isTrue();
        // 《开发顺序》M5 验收标准 1：改描述后立即「分析已过期」
        assertThat(view.isStale()).isTrue();
    }

    // ---------------------------------------------------------------- 测试数据

    private static Job job(String description) {
        Job job = new Job();
        job.setId(5L);
        job.setUserId(1L);
        job.setCompanyName("示例科技");
        job.setJobName("Java 后端开发实习生");
        job.setDescription(description);
        return job;
    }

    private static JobAnalysis analysis(Long id, Long jobId, int versionNo, String sourceHash) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setId(id);
        analysis.setJobId(jobId);
        analysis.setVersionNo(versionNo);
        analysis.setSourceHash(sourceHash);
        analysis.setSummary("已有分析");
        analysis.setStatus(JobAnalysis.STATUS_SUCCESS);
        analysis.setModelName("deepseek-flash");
        analysis.setCreatedAt(LocalDateTime.now());
        return analysis;
    }

    /** 仅带 versionNo 的记录：模拟 nextVersionNo 的 select(versionNo) 查询结果 */
    private static JobAnalysis version(int versionNo) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setVersionNo(versionNo);
        return analysis;
    }

    private static AiCallResult<JobAnalysisAiOutput> aiResult(String summary) {
        JobAnalysisAiOutput output = new JobAnalysisAiOutput();
        output.setSummary(summary);
        output.setResponsibilities(List.of("参与核心业务系统开发"));
        output.setRequiredSkills(List.of("Java", "Spring Boot"));
        return new AiCallResult<>(output, "deepseek-flash", 1200L, 100, 50, 150);
    }

    /** 测试内的独立 hash 实现：不复用生产代码，保证"复用的判定依据"被独立验证 */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

}
