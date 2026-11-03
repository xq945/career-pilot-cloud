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
import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.Resume;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 简历工具（《Agent设计》§4.2）：身份取自认证上下文、归属 404 冒泡（由包装器转失败信封）、
 * 成功时返回精简投影（不含简历原文）。
 */
class ResumeAgentToolsTest {

    private ResumeService resumeService;

    private ResumeAnalysisService analysisService;

    private ResumeAgentTools tools;

    @BeforeEach
    void setUp() {
        resumeService = mock(ResumeService.class);
        analysisService = mock(ResumeAnalysisService.class);
        tools = new ResumeAgentTools(resumeService, analysisService);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(7L, "tester"), null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void analyzeResume_notOwned_throwsNotFound() {
        when(resumeService.getOwnedResume(7L, 99L))
                .thenThrow(BusinessException.notFound("简历不存在"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tools.analyzeResume(99L));

        assertEquals(40400, ex.getCode());
    }

    @Test
    void analyzeResume_success_returnsEnvelopeWithCompactProjection() {
        Resume resume = resume(12L, "Java 后端简历");
        when(resumeService.getOwnedResume(7L, 12L)).thenReturn(resume);
        when(analysisService.analyze(resume)).thenReturn(detail());

        String payload = tools.analyzeResume(12L);

        assertTrue(payload.contains("\"status\":\"SUCCESS\""), payload);
        assertTrue(payload.contains("\"resumeId\":12"), payload);
        assertTrue(payload.contains("Java 后端简历"), payload);
        assertTrue(payload.contains("\"versionNo\":2"), payload);
        // 技能投影为 name（level）形式；简历原文不进工具结果
        assertTrue(payload.contains("Java（熟练）"), payload);
    }

    @Test
    void analyzeResume_blankContent_analysisRejectsAndThrows() {
        // AG-04：空文本由 ResumeAnalysisService.analyze 拒绝（400），不进入模型；
        // 包装器再把它转为 FAILED 信封交给模型说明（见 RecordingToolCallbackTest）
        Resume resume = resume(12L, "空简历");
        when(resumeService.getOwnedResume(7L, 12L)).thenReturn(resume);
        when(analysisService.analyze(resume))
                .thenThrow(BusinessException.badRequest("简历文本为空，无法分析"));

        BusinessException ex = assertThrows(BusinessException.class, () -> tools.analyzeResume(12L));

        assertEquals(40000, ex.getCode());
    }

    // ---------------------------------------------------------------- 测试数据

    private static Resume resume(Long id, String name) {
        Resume resume = new Resume();
        resume.setId(id);
        resume.setUserId(7L);
        resume.setName(name);
        resume.setContent("（简历正文，不应出现在工具结果中）");
        return resume;
    }

    private static ResumeAnalysisDetail detail() {
        SkillItem skill = new SkillItem();
        skill.setName("Java");
        skill.setLevel("熟练");

        ResumeAnalysisDetail detail = new ResumeAnalysisDetail();
        detail.setResumeId(12L);
        detail.setVersionNo(2);
        detail.setSummary("本科应届，具备 Java 后端开发经验");
        detail.setSkills(List.of(skill));
        detail.setAdvantages(List.of("项目经验扎实"));
        detail.setWeaknesses(List.of("缺少部署经验"));
        detail.setSuggestions(List.of("补一个容器化部署场景"));
        return detail;
    }

}
