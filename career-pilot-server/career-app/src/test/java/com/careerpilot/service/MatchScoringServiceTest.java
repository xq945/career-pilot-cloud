package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.careerpilot.config.MatchScoringProperties;
import com.careerpilot.dto.MatchDimension;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.ai.EducationItem;
import com.careerpilot.dto.ai.ExperienceItem;
import com.careerpilot.dto.ai.ProjectItem;
import com.careerpilot.dto.ai.SkillItem;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 评分规则的护栏（《数据库设计》6）。
 *
 * 这是「模型不能覆盖总分」的**确定性证明**：评分是纯函数的输入输出映射，与模型无关。
 * 主用例 80/75/70/80/50 → 75.75 与《API接口设计》6.1 的响应示例同源——
 * 文档、示例与实现一旦漂移，这里最先报警。
 */
class MatchScoringServiceTest {

    private static final MatchScoringProperties PROPERTIES =
            new MatchScoringProperties("v1", new MatchScoringProperties.Weights(45, 25, 15, 10, 5));

    private final MatchScoringService service = new MatchScoringService(PROPERTIES);

    // ---------------------------------------------------------------- 主用例与整体规则

    @Test
    void score_matchesApiExampleWeights() {
        JobAnalysis job = job(
                List.of("Java", "Spring Boot", "MySQL", "Redis", "Docker"),
                List.of("Kafka"),
                List.of("Kafka", "Kubernetes"),
                "2.5年以上开发经验",
                "博士");
        ResumeAnalysis resume = resume(
                List.of("Java", "Spring Boot", "MySQL", "Redis", "Kafka"),
                List.of(project("电商平台", "后端", List.of("Java", "Spring Boot", "MySQL", "Redis"),
                        "基于 Kafka 的消息队列改造")),
                List.of(experience("示例公司", "后端实习生", "2024.01-2025.10")),
                List.of(education("示例大学", "计算机", "硕士")),
                "熟悉后端开发");

        MatchScoringResult result = service.score(job, resume);

        // 必备技能 4/5=80；项目相关性 (4+0.5)/6=75；经历 21/30 月=70；学历 硕士/博士=80；加分技能 1/2=50
        assertEquals(0, new BigDecimal("75.75").compareTo(result.totalScore()));
        assertEquals(List.of(80, 75, 70, 80, 50), scoresOf(result));
        assertEquals(List.of(45, 25, 15, 10, 5), weightsOf(result));
        assertEquals(List.of("requiredSkills", "projectRelevance", "experience", "education",
                "preferredSkills"), keysOf(result));
        assertEquals("v1", result.dimensionScores().getRuleVersion());
    }

    @Test
    void score_dimensionWithoutRequirementGetsFullMarks() {
        JobAnalysis job = job(List.of("Java"), List.of(), List.of(), null, null);
        ResumeAnalysis resume = resume(List.of("Java"),
                List.of(project("示例项目", "开发", List.of("Java"), null)), List.of(), List.of(), null);

        MatchScoringResult result = service.score(job, resume);

        // 岗位未提出的要求不扣分：五个维度均为 100，总分可达上限
        assertEquals(List.of(100, 100, 100, 100, 100), scoresOf(result));
        assertEquals(0, new BigDecimal("100.00").compareTo(result.totalScore()));
    }

    @Test
    void score_requirementsWithoutEvidenceGiveZeroAndControlledWording() {
        JobAnalysis job = job(List.of("Java", "Docker"), null, null, null, null);
        ResumeAnalysis resume = resume(List.of(), List.of(), List.of(), List.of(), null);

        MatchScoringResult result = service.score(job, resume);

        assertEquals(0, scoreOf(result, MatchDimension.REQUIRED_SKILLS));
        List<String> explanations = result.evidences().stream()
                .map(ScoringEvidence::explanation).toList();
        assertTrue(explanations.stream().anyMatch(text -> text.contains("Java") && text.contains("未体现")),
                "差距文案必须含「未体现」：" + explanations);
        assertTrue(explanations.stream().noneMatch(text -> text.contains("不具备")),
                "全链路禁止「不具备」类断言：" + explanations);
    }

    @Test
    void score_projectsEmptyGivesZeroForProjectRelevance() {
        JobAnalysis job = job(List.of("Java"), List.of(), null, null, null);
        ResumeAnalysis resume = resume(List.of("Java"), List.of(), List.of(), List.of(), null);

        // 技能自述不算项目落点：必备技能命中，项目相关性因缺少项目经历记 0（《数据库设计》6.4）
        MatchScoringResult result = service.score(job, resume);
        assertEquals(100, scoreOf(result, MatchDimension.REQUIRED_SKILLS));
        assertEquals(0, scoreOf(result, MatchDimension.PROJECT_RELEVANCE));
    }

    @Test
    void score_ledgerTracesTermStatusAndSource() {
        JobAnalysis job = job(List.of("Java"), null, null, null, null);
        ResumeAnalysis resume = resume(List.of("Java"), null, null, null, null);

        ScoringEvidence evidence = service.score(job, resume).evidences().stream()
                .filter(item -> item.dimension() == MatchDimension.REQUIRED_SKILLS)
                .findFirst().orElseThrow();

        assertEquals("Java", evidence.term());
        assertEquals(ScoringEvidence.Status.MATCHED, evidence.status());
        assertEquals("skills.name", evidence.source());
    }

    // ---------------------------------------------------------------- 术语匹配

    @Test
    void matcher_latinTermsRespectTokenBoundary() {
        assertEquals(SkillTermMatcher.Hit.MISS, SkillTermMatcher.match("Java", termsOf("JavaScript")).hit());
        assertEquals(SkillTermMatcher.Hit.MISS, SkillTermMatcher.match("Go", termsOf("Django")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("Java", termsOf("JavaScript", "Java")).hit());
    }

    @Test
    void matcher_normalizationAndAliases() {
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("Spring Boot", termsOf("SpringBoot")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("Node.js", termsOf("NodeJS")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("JS", termsOf("JavaScript")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("K8s", termsOf("Kubernetes")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("ＭｙＳＱＬ", termsOf("mysql")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("Vue", termsOf("Vue 3")).hit());
        assertEquals(SkillTermMatcher.Hit.DIRECT, SkillTermMatcher.match("SpringBoot框架", termsOf("Spring Boot")).hit());
    }

    @Test
    void matcher_partialWhenOnlyWeakEvidence() {
        SkillTermMatcher.ResumeTerms terms = new SkillTermMatcher.ResumeTerms(List.of(),
                List.of("参与 Kafka 消息队列改造"));
        SkillTermMatcher.TermMatch match = SkillTermMatcher.match("Kafka", terms);
        assertEquals(SkillTermMatcher.Hit.PARTIAL, match.hit());
        assertEquals(SkillTermMatcher.WEAK_SOURCE, match.source());
    }

    @Test
    void matcher_missingReturnsNoSource() {
        SkillTermMatcher.TermMatch match = SkillTermMatcher.match("Kafka", termsOf("Java"));
        assertEquals(SkillTermMatcher.Hit.MISS, match.hit());
        assertNull(match.source());
    }

    // ---------------------------------------------------------------- 经历维度

    @Test
    void experience_byMonthsRatio() {
        assertEquals(58, experienceScore("3年以上", List.of(experience("A", "实习", "2024.01-2025.10"))));
        assertEquals(70, experienceScore("2.5年以上", List.of(experience("A", "实习", "2024.01-2025.10"))));
        // 只写年份的区间：2024-2025 → 12 个月
        assertEquals(33, experienceScore("3年以上", List.of(experience("A", "实习", "2024-2025"))));
    }

    @Test
    void experience_presenceRuleWhenRequirementHasNoYears() {
        assertEquals(100, experienceScore("有实习经历优先", List.of(experience("A", "实习", "2024.01-2025.10"))));
        assertEquals(0, experienceScore("有实习经历优先", List.of()));
    }

    @Test
    void experience_unparsablePeriodFallsBackToPresence() {
        assertEquals(100, experienceScore("3年以上", List.of(experience("A", "实习", "时间不详"))));
    }

    @Test
    void experience_noRequirementGivesFullMarks() {
        assertEquals(100, experienceScore(null, List.of()));
        assertEquals(100, experienceScore("不限", List.of()));
    }

    // ---------------------------------------------------------------- 学历维度

    @Test
    void education_levelRatio() {
        assertEquals(100, educationScore("本科及以上", List.of(education("A", "计算机", "硕士"))));
        assertEquals(100, educationScore("本科", List.of(education("A", "计算机", "本科"))));
        assertEquals(67, educationScore("本科", List.of(education("A", "计算机", "大专"))));
        assertEquals(80, educationScore("博士", List.of(education("A", "计算机", "硕士"))));
    }

    @Test
    void education_noRequirementOrUnknownLevelGivesFullMarks() {
        assertEquals(100, educationScore("不限", List.of()));
        assertEquals(100, educationScore("学历不限，能力优先", List.of()));
        assertEquals(100, educationScore("其他要求", List.of()));
    }

    @Test
    void education_missingDegreeGivesZeroWithWording() {
        JobAnalysis job = job(null, null, null, null, "本科");
        ResumeAnalysis resume = resume(null, null, null, null, null);

        MatchScoringResult result = service.score(job, resume);

        assertEquals(0, scoreOf(result, MatchDimension.EDUCATION));
        assertTrue(result.evidences().stream()
                        .anyMatch(evidence -> evidence.dimension() == MatchDimension.EDUCATION
                                && evidence.explanation().contains("未体现")),
                "缺少学历证据时账本必须含「未体现」文案");
    }

    // ---------------------------------------------------------------- 辅助

    private static List<Integer> scoresOf(MatchScoringResult result) {
        return result.dimensionScores().getItems().stream().map(MatchDimensionScore::getScore).toList();
    }

    private static List<Integer> weightsOf(MatchScoringResult result) {
        return result.dimensionScores().getItems().stream().map(MatchDimensionScore::getWeight).toList();
    }

    private static List<String> keysOf(MatchScoringResult result) {
        return result.dimensionScores().getItems().stream().map(MatchDimensionScore::getKey).toList();
    }

    private static int scoreOf(MatchScoringResult result, MatchDimension dimension) {
        return result.dimensionScores().getItems().stream()
                .filter(item -> item.getKey().equals(dimension.getKey()))
                .findFirst().orElseThrow().getScore();
    }

    private int experienceScore(String requirement, List<ExperienceItem> items) {
        return scoreOf(service.score(job(null, null, null, requirement, null),
                resume(null, null, items, null, null)), MatchDimension.EXPERIENCE);
    }

    private int educationScore(String requirement, List<EducationItem> items) {
        return scoreOf(service.score(job(null, null, null, null, requirement),
                resume(null, null, null, items, null)), MatchDimension.EDUCATION);
    }

    private static SkillTermMatcher.ResumeTerms termsOf(String... names) {
        return new SkillTermMatcher.ResumeTerms(
                java.util.Arrays.stream(names)
                        .map(name -> new SkillTermMatcher.Evidence(name, "skills.name")).toList(),
                List.of());
    }

    private static JobAnalysis job(List<String> requiredSkills, List<String> technologyStack,
                                   List<String> preferredSkills, String experienceRequirement,
                                   String educationRequirement) {
        JobAnalysis analysis = new JobAnalysis();
        analysis.setRequiredSkills(requiredSkills);
        analysis.setTechnologyStack(technologyStack);
        analysis.setPreferredSkills(preferredSkills);
        analysis.setExperienceRequirement(experienceRequirement);
        analysis.setEducationRequirement(educationRequirement);
        return analysis;
    }

    private static ResumeAnalysis resume(List<String> skillNames, List<ProjectItem> projects,
                                         List<ExperienceItem> experience, List<EducationItem> education,
                                         String summary) {
        ResumeAnalysis analysis = new ResumeAnalysis();
        analysis.setSkills(skillNames == null ? null : skillNames.stream().map(name -> {
            SkillItem item = new SkillItem();
            item.setName(name);
            return item;
        }).toList());
        analysis.setProjects(projects);
        analysis.setExperience(experience);
        analysis.setEducation(education);
        analysis.setSummary(summary);
        return analysis;
    }

    private static ProjectItem project(String name, String role, List<String> technologies, String description) {
        ProjectItem item = new ProjectItem();
        item.setName(name);
        item.setRole(role);
        item.setTechnologies(technologies);
        item.setDescription(description);
        return item;
    }

    private static ExperienceItem experience(String company, String position, String period) {
        ExperienceItem item = new ExperienceItem();
        item.setCompany(company);
        item.setPosition(position);
        item.setPeriod(period);
        return item;
    }

    private static EducationItem education(String school, String major, String degree) {
        EducationItem item = new EducationItem();
        item.setSchool(school);
        item.setMajor(major);
        item.setDegree(degree);
        return item;
    }

}
