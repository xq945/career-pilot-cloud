package com.careerpilot.service.skill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 技能加载与题量分配（M15）：读取**真实的资源文件**（SKILL.md + skill.meta.yml + references），
 * 验证三阶分配算法与配额守恒——分配算法是"模型按配额出题"的确定性依据，
 * 也是"配额不足不静默"的第一道护栏。
 */
class InterviewSkillServiceTest {

    private InterviewSkillService service;

    @BeforeEach
    void setUp() {
        service = new InterviewSkillService();
        service.loadSkills();
    }

    @Test
    @DisplayName("技能加载：java-backend 含五个分类（PROJECT 保底 / 双 CORE / 双 NORMAL）且 persona 非空")
    void loadsSkillDefinition() {
        InterviewSkillService.SkillDefinition skill = service.currentSkill();
        assertEquals("java-backend", skill.skillId());
        assertFalse(skill.persona().isBlank());
        assertEquals(List.of("PROJECT", "JAVA", "SPRING", "MYSQL", "REDIS"),
                skill.categories().stream()
                        .map(InterviewSkillService.CategoryDefinition::key).toList());
        Map<String, InterviewSkillService.CategoryDefinition> byKey = skill.categoriesByKey();
        assertEquals(InterviewSkillService.Priority.ALWAYS_ONE, byKey.get("PROJECT").priority());
        assertEquals(InterviewSkillService.Priority.CORE, byKey.get("JAVA").priority());
        assertEquals(InterviewSkillService.Priority.NORMAL, byKey.get("MYSQL").priority());
    }

    @Test
    @DisplayName("单题型直通：全部名额给该分类")
    void singleTypeGoesStraight() {
        assertEquals(Map.of("JAVA", 5), service.calculateAllocation("JAVA", 5));
        assertEquals(Map.of("PROJECT", 3), service.calculateAllocation("PROJECT", 3));
        assertEquals(Map.of("REDIS", 1), service.calculateAllocation("REDIS", 1));
    }

    @Test
    @DisplayName("MIXED 三阶：保底 → CORE 各 1 → NORMAL 各 1 → 剩余在 CORE 间轮转")
    void mixedAllocationFollowsThreeStages() {
        assertEquals(Map.of("PROJECT", 1), service.calculateAllocation("MIXED", 1));

        Map<String, Integer> two = service.calculateAllocation("MIXED", 2);
        assertEquals(1, two.get("PROJECT"));
        assertEquals(1, two.get("JAVA"));
        assertEquals(2, two.size());

        Map<String, Integer> three = service.calculateAllocation("MIXED", 3);
        assertEquals(1, three.get("PROJECT"));
        assertEquals(1, three.get("JAVA"));
        assertEquals(1, three.get("SPRING"));

        Map<String, Integer> five = service.calculateAllocation("MIXED", 5);
        assertEquals(5, five.size());
        assertTrue(five.values().stream().allMatch(value -> value == 1));

        // count=20：五类各 1 后剩余 15 在 JAVA/SPRING 间轮转 → JAVA 9 / SPRING 8
        Map<String, Integer> twenty = service.calculateAllocation("MIXED", 20);
        assertEquals(9, twenty.get("JAVA"));
        assertEquals(8, twenty.get("SPRING"));
        assertEquals(1, twenty.get("PROJECT"));
        assertEquals(1, twenty.get("MYSQL"));
        assertEquals(1, twenty.get("REDIS"));
    }

    @Test
    @DisplayName("配额守恒：题数 1–20 下配额之和恒等于题数，0 题返回空")
    void allocationAlwaysSumsToCount() {
        for (int count = 1; count <= 20; count++) {
            Map<String, Integer> mixed = service.calculateAllocation("MIXED", count);
            assertEquals(count, mixed.values().stream().mapToInt(Integer::intValue).sum(),
                    "MIXED count=" + count);
            Map<String, Integer> single = service.calculateAllocation("MYSQL", count);
            assertEquals(count, single.values().stream().mapToInt(Integer::intValue).sum(),
                    "MYSQL count=" + count);
        }
        assertTrue(service.calculateAllocation("MIXED", 0).isEmpty());
    }

    @Test
    @DisplayName("参考要点：按配额取对应分类、单题型的配额只注入该分类、总量受控")
    void referenceSectionRespectsAllocation() {
        Map<String, Integer> allocation = service.calculateAllocation("MIXED", 5);
        String section = service.buildReferenceSection(allocation);
        assertFalse(section.isBlank());
        assertTrue(section.contains("Java 基础"));
        assertTrue(section.contains("项目经历"));
        // 总预算 6000 字符 + 截断标记余量
        assertTrue(section.length() <= 6600, "参考要点总长应受预算约束，实际 " + section.length());

        String single = service.buildReferenceSection(Map.of("JAVA", 3));
        assertTrue(single.contains("Java 基础"));
        assertFalse(single.contains("MySQL 参考要点"), "单题型不应注入其他分类的参考要点");
    }

    @Test
    @DisplayName("配额描述文本：逐分类列出题数")
    void allocationDescriptionListsCounts() {
        String description = service.buildAllocationDescription(service.calculateAllocation("MIXED", 3));
        assertTrue(description.contains("项目经历（PROJECT）：1 题"));
        assertTrue(description.contains("Java 基础（JAVA）：1 题"));
        assertTrue(description.contains("Spring 框架（SPRING）：1 题"));
    }

}
