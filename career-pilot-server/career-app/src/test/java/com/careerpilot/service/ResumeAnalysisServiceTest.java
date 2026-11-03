package com.careerpilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.dto.ai.ResumeAnalysisAiOutput;
import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.ResumeAnalysisMapper;
import com.careerpilot.service.ai.ResumeAiService;

/**
 * 简历分析业务编排的单测（《测试与验收方案》1.1：分析版本与落库）。
 *
 * 重点验证「每次重新分析都生成新版本（2026-10-13 起不做复用判断）」与「失败不落库」，
 * 全部使用 Mockito 替身，不依赖真实模型与数据库。
 */
@ExtendWith(MockitoExtension.class)
class ResumeAnalysisServiceTest {

    private static final String CONTENT = "姓名：张三\n技能：Java、Spring Boot\n项目：求职平台";

    @Mock
    private ResumeService resumeService;

    @Mock
    private ResumeAiService resumeAiService;

    @Mock
    private ResumeAnalysisMapper resumeAnalysisMapper;

    private ResumeAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new ResumeAnalysisService(resumeService, resumeAiService, resumeAnalysisMapper);
    }

    // ---------------------------------------------------------------- 前置校验

    @Test
    void analyze_blankContent_throwsBadRequestWithoutTouchingModelOrDb() {
        Resume resume = resume("   ");

        assertThatThrownBy(() -> service.analyze(resume))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40000));
        verifyNoInteractions(resumeAiService, resumeAnalysisMapper);
    }

    // ---------------------------------------------------------------- 每次重新生成（2026-10-13 语义变更）

    @Test
    void analyze_contentUnchanged_stillGeneratesNewVersion() {
        Resume resume = resume(CONTENT);
        // 库里已有同源分析（v1），但「重新分析」不复用：仍调用模型并生成新版本
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(analysis(null, 1L, 1, null));
        when(resumeAiService.analyze(CONTENT)).thenReturn(aiResult("重新分析结果"));
        when(resumeAnalysisMapper.insert(any(ResumeAnalysis.class))).thenAnswer(invocation -> {
            ResumeAnalysis inserted = invocation.getArgument(0);
            inserted.setId(40L);
            return 1;
        });

        ResumeAnalysisDetail detail = service.analyze(resume);

        assertThat(detail.getAnalysisId()).isEqualTo(40L);
        assertThat(detail.getVersionNo()).isEqualTo(2);
        assertThat(detail.getSummary()).isEqualTo("重新分析结果");
        // 内容未变化时模型仍被调用（与旧语义相反：《开发顺序》M4 验收标准已随拍板修订）
        verify(resumeAiService).analyze(CONTENT);
    }

    // ---------------------------------------------------------------- 新版本

    @Test
    void analyze_changedContent_generatesNextVersion() {
        Resume resume = resume(CONTENT);
        // 唯一一次 selectOne 是 nextVersionNo（已有 v2 → 新版本 v3）
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(analysis(null, 1L, 2, null));
        when(resumeAiService.analyze(CONTENT)).thenReturn(aiResult("新版本结果"));
        when(resumeAnalysisMapper.insert(any(ResumeAnalysis.class))).thenAnswer(invocation -> {
            ResumeAnalysis inserted = invocation.getArgument(0);
            inserted.setId(20L);
            return 1;
        });

        ResumeAnalysisDetail detail = service.analyze(resume);

        assertThat(detail.getAnalysisId()).isEqualTo(20L);
        assertThat(detail.getVersionNo()).isEqualTo(3);
        assertThat(detail.getSummary()).isEqualTo("新版本结果");
        verify(resumeAiService).analyze(CONTENT);
    }

    @Test
    void analyze_noHistory_startsFromVersionOne() {
        Resume resume = resume(CONTENT);
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(null);
        when(resumeAiService.analyze(CONTENT)).thenReturn(aiResult("首次分析"));
        when(resumeAnalysisMapper.insert(any(ResumeAnalysis.class))).thenAnswer(invocation -> {
            ResumeAnalysis inserted = invocation.getArgument(0);
            inserted.setId(30L);
            return 1;
        });

        ResumeAnalysisDetail detail = service.analyze(resume);

        assertThat(detail.getVersionNo()).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 并发兜底

    @Test
    void analyze_versionConflict_retriesAndSucceeds() {
        Resume resume = resume(CONTENT);
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(null);
        when(resumeAiService.analyze(CONTENT)).thenReturn(aiResult("结果"));
        doAnswer(invocation -> {
            throw new DuplicateKeyException("uk_resume_analysis_version");
        }).doAnswer(invocation -> {
            ResumeAnalysis inserted = invocation.getArgument(0);
            inserted.setId(40L);
            return 1;
        }).when(resumeAnalysisMapper).insert(any(ResumeAnalysis.class));

        ResumeAnalysisDetail detail = service.analyze(resume);

        assertThat(detail.getAnalysisId()).isEqualTo(40L);
        // 版本冲突重试期间不重复调用模型（不重复计费）
        verify(resumeAiService).analyze(CONTENT);
    }

    // ---------------------------------------------------------------- 失败不落库

    @Test
    void analyze_modelFailure_propagatesAndWritesNothing() {
        Resume resume = resume(CONTENT);
        // 模型先于 nextVersionNo（selectOne）抛出，无需打桩版本查询
        when(resumeAiService.analyze(CONTENT)).thenThrow(BusinessException.aiUnavailable("模型不可用"));

        assertThatThrownBy(() -> service.analyze(resume))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(50200));
        verify(resumeAnalysisMapper, never()).insert(any(ResumeAnalysis.class));
    }

    // ---------------------------------------------------------------- 4.6 查询

    @Test
    void getLatest_withoutAnalysis_returnsNotAnalyzed() {
        Resume resume = resume(CONTENT);
        when(resumeService.getOwnedResume(1L, 5L)).thenReturn(resume);
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(null);

        ResumeAnalysisView view = service.getLatest(1L, 5L);

        assertThat(view.isAnalyzed()).isFalse();
        assertThat(view.getAnalysis()).isNull();
    }

    @Test
    void getLatest_withAnalysis_returnsDetail() {
        Resume resume = resume(CONTENT);
        when(resumeService.getOwnedResume(1L, 5L)).thenReturn(resume);
        when(resumeAnalysisMapper.selectOne(any())).thenReturn(analysis(10L, 5L, 1, sha256(CONTENT)));

        ResumeAnalysisView view = service.getLatest(1L, 5L);

        assertThat(view.isAnalyzed()).isTrue();
        assertThat(view.getAnalysis().getAnalysisId()).isEqualTo(10L);
    }

    // ---------------------------------------------------------------- 测试数据

    private static Resume resume(String content) {
        Resume resume = new Resume();
        resume.setId(5L);
        resume.setUserId(1L);
        resume.setContent(content);
        return resume;
    }

    private static ResumeAnalysis analysis(Long id, Long resumeId, int versionNo, String sourceHash) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setId(id);
        analysis.setResumeId(resumeId);
        analysis.setVersionNo(versionNo);
        analysis.setSourceHash(sourceHash);
        analysis.setSummary("已有分析");
        analysis.setStatus(ResumeAnalysis.STATUS_SUCCESS);
        analysis.setModelName("deepseek-flash");
        analysis.setCreatedAt(LocalDateTime.now());
        return analysis;
    }

    private static AiCallResult<ResumeAnalysisAiOutput> aiResult(String summary) {
        ResumeAnalysisAiOutput output = new ResumeAnalysisAiOutput();
        output.setSummary(summary);
        SkillItem skill = new SkillItem();
        skill.setName("Java");
        output.setSkills(List.of(skill));
        return new AiCallResult<>(output, "deepseek-flash", 1200L, 100, 50, 150);
    }

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
