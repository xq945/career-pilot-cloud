package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 历史分析结构校验（审查报告 S2）的护栏。
 *
 * 关键断言是两类"静默错误"的防线：**空白串被当成"岗位有要求"**（导致误扣分）、
 * **缺 name 的技能项扩散进计分与模型上下文**。清洗只作用于内存对象，不写库、不报错。
 */
class AnalysisStructureCheckTest {

    @Test
    void sanitize_dropsSkillsWithoutNameAndNormalizesBlanks() {
        ResumeAnalysis analysis = new ResumeAnalysis();
        SkillItem ok = new SkillItem();
        ok.setName("Java");
        SkillItem missingName = new SkillItem();
        SkillItem blankName = new SkillItem();
        blankName.setName("  ");
        analysis.setSkills(List.of(ok, missingName, blankName));
        analysis.setSummary("   ");
        analysis.setProjects(null);
        analysis.setAdvantages(Arrays.asList("  优势  ", "", null));

        AnalysisStructureCheck.sanitize(analysis, "test");

        assertEquals(1, analysis.getSkills().size());
        assertEquals("Java", analysis.getSkills().get(0).getName());
        assertNull(analysis.getSummary(), "空白串必须归一为 null，否则会被误判为「有要求」");
        assertTrue(analysis.getProjects().isEmpty(), "null 列表必须补为空列表");
        assertEquals(List.of("  优势  "), analysis.getAdvantages());
    }

    @Test
    void sanitize_normalizesBlankRequirementToNull() {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setEducationRequirement("   ");
        analysis.setExperienceRequirement("");
        analysis.setRequiredSkills(null);
        analysis.setTechnologyStack(Arrays.asList("Java", "  "));

        AnalysisStructureCheck.sanitize(analysis, "test");

        assertNull(analysis.getEducationRequirement());
        assertNull(analysis.getExperienceRequirement());
        assertTrue(analysis.getRequiredSkills().isEmpty());
        assertEquals(List.of("Java"), analysis.getTechnologyStack());
    }

    @Test
    void sanitize_truncatesOversizedLists() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add("技能" + i);
        }
        JobAnalysis analysis = new JobAnalysis();
        analysis.setRequiredSkills(many);

        AnalysisStructureCheck.sanitize(analysis, "test");

        assertEquals(50, analysis.getRequiredSkills().size(), "超长列表必须截断到上限");
    }

    @Test
    void sanitize_toleratesNullAnalysis() {
        AnalysisStructureCheck.sanitize((ResumeAnalysis) null, "test");
        AnalysisStructureCheck.sanitize((JobAnalysis) null, "test");
    }

}
