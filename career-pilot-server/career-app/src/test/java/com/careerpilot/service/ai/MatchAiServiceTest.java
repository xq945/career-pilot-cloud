package com.careerpilot.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.careerpilot.ai.AiCallResult;
import com.careerpilot.ai.AiCallTemplate;
import com.careerpilot.dto.MatchDimension;
import com.careerpilot.dto.MatchDimensionScore;
import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.ai.MatchExplanationAiOutput;
import com.careerpilot.dto.ai.MatchItem;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.service.MatchScoringResult;
import com.careerpilot.service.ScoringEvidence;

/**
 * 匹配解释清洗与账本补全的护栏（《Agent设计》6.4、《API接口设计》6.1）。
 *
 * 覆盖三条硬要求：模型输出中的越界分数被忽略（不能覆盖总分）、dimension 越界置 null、
 * 模型漏掉的明细按证据账本补全且文案受控（含「未体现」）。
 */
class MatchAiServiceTest {

    private AiCallTemplate template;

    private MatchAiService service;

    @BeforeEach
    void setUp() {
        template = mock(AiCallTemplate.class);
        // 读真实提示词资源：模板缺失同样让测试失败（与"启动即失败"的约定一致）
        service = new MatchAiService(template, new ClassPathResource("prompts/match-explanation.system.txt"));
    }

    @Test
    void sanitize_dimensionOutOfRangeBecomesNull() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMatchedItems(List.of(item("Java：项目中有使用", "programming")));

        MatchExplanationAiOutput cleaned = run(output, scoring(List.of()));

        assertEquals(1, cleaned.getMatchedItems().size());
        assertNull(cleaned.getMatchedItems().get(0).getDimension(), "越界 dimension 必须置 null 而非丢弃整条");
    }

    @Test
    void sanitize_topLevelScoreFieldsAreDropped() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        // 模拟模型试图输出/覆盖分数：输出类型无这些字段，只会被收集
        output.putUnknownField("matchScore", 150);
        output.putUnknownField("dimensionScores", List.of());

        MatchExplanationAiOutput cleaned = run(output, scoring(List.of()));

        assertTrue(cleaned.drainUnknownFields().isEmpty(), "清洗后约定外字段必须已被取出清空");
    }

    @Test
    void sanitize_truncatesListsAndText() {
        List<MatchItem> many = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            many.add(item("差距证据 " + i, "requiredSkills"));
        }
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMissingItems(many);
        output.setSummary("很长的总评".repeat(600));
        output.setSuggestions(new ArrayList<>(Collections.nCopies(25, "建议")));

        MatchExplanationAiOutput cleaned = run(output, scoring(List.of()));

        assertEquals(50, cleaned.getMissingItems().size());
        assertEquals(2000, cleaned.getSummary().length());
        assertEquals(20, cleaned.getSuggestions().size());
    }

    @Test
    void sanitize_dropsItemsWithForbiddenWording() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMissingItems(List.of(
                item("Docker：你不具备容器化能力", "requiredSkills"),
                item("Redis：简历未体现相关证据", "requiredSkills")));
        output.setSummary("该候选人不具备该岗位要求");
        output.setSuggestions(List.of("先补齐 Docker 相关经验", "你不具备该技能"));

        MatchExplanationAiOutput cleaned = run(output, scoring(List.of()));

        assertEquals(1, cleaned.getMissingItems().size(), "命中禁用措辞的明细必须丢弃");
        assertTrue(cleaned.getMissingItems().get(0).getText().contains("未体现"));
        assertNull(cleaned.getSummary());
        assertEquals(List.of("先补齐 Docker 相关经验"), cleaned.getSuggestions());
    }

    @Test
    void sanitize_fillsMissingItemsFromLedger() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMissingItems(new ArrayList<>());
        MatchScoringResult scoring = scoring(List.of(
                new ScoringEvidence(MatchDimension.REQUIRED_SKILLS, "Docker",
                        ScoringEvidence.Status.MISSING, null),
                new ScoringEvidence(MatchDimension.REQUIRED_SKILLS, "Java",
                        ScoringEvidence.Status.MATCHED, "skills.name")));

        MatchExplanationAiOutput cleaned = run(output, scoring);

        assertEquals(1, cleaned.getMissingItems().size(), "只有 MISSING 档进入差距项，MATCHED 不补");
        assertEquals("Docker：简历未体现相关证据", cleaned.getMissingItems().get(0).getText());
        assertEquals("requiredSkills", cleaned.getMissingItems().get(0).getDimension());
    }

    @Test
    void sanitize_fillsPartialItemsFromLedger() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setPartialItems(new ArrayList<>());
        MatchScoringResult scoring = scoring(List.of(
                new ScoringEvidence(MatchDimension.PROJECT_RELEVANCE, "Kafka",
                        ScoringEvidence.Status.PARTIAL, "text")));

        MatchExplanationAiOutput cleaned = run(output, scoring);

        assertEquals(1, cleaned.getPartialItems().size());
        assertTrue(cleaned.getPartialItems().get(0).getText().contains("缺少"),
                "部分匹配补全文案必须含「缺少」：" + cleaned.getPartialItems().get(0).getText());
    }

    @Test
    void sanitize_ledgerFillSkipsAlreadyMentionedTerms() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();
        output.setMissingItems(new ArrayList<>(List.of(item("Docker：简历未体现相关证据", "requiredSkills"))));
        MatchScoringResult scoring = scoring(List.of(
                new ScoringEvidence(MatchDimension.REQUIRED_SKILLS, "Docker",
                        ScoringEvidence.Status.MISSING, null)));

        MatchExplanationAiOutput cleaned = run(output, scoring);

        assertEquals(1, cleaned.getMissingItems().size(), "模型已提及的术语不得重复追加");
    }

    @Test
    void sanitize_nullListsBecomeEmpty() {
        MatchExplanationAiOutput output = new MatchExplanationAiOutput();

        MatchExplanationAiOutput cleaned = run(output, scoring(List.of()));

        assertEquals(List.of(), cleaned.getMatchedItems());
        assertEquals(List.of(), cleaned.getPartialItems());
        assertEquals(List.of(), cleaned.getMissingItems());
        assertEquals(List.of(), cleaned.getSuggestions());
        assertNull(cleaned.getSummary());
    }

    // ---------------------------------------------------------------- 辅助

    private MatchExplanationAiOutput run(MatchExplanationAiOutput output, MatchScoringResult scoring) {
        when(template.call(eq("match-explanation"), anyString(), anyString(), eq(MatchExplanationAiOutput.class)))
                .thenReturn(new AiCallResult<>(output, "test-model", 100L, 1, 1, 2));
        return service.explain(new JobAnalysis(), new ResumeAnalysis(), scoring).value();
    }

    private static MatchScoringResult scoring(List<ScoringEvidence> evidences) {
        List<MatchDimensionScore> items = List.of(
                new MatchDimensionScore("requiredSkills", "必备技能", 45, 0),
                new MatchDimensionScore("projectRelevance", "项目经历相关性", 25, 0),
                new MatchDimensionScore("experience", "工作/实习经历", 15, 0),
                new MatchDimensionScore("education", "学历与经验要求", 10, 0),
                new MatchDimensionScore("preferredSkills", "加分技能", 5, 0));
        return new MatchScoringResult(new BigDecimal("0.00"),
                new MatchDimensionScores("v1", items), evidences);
    }

    private static MatchItem item(String text, String dimension) {
        MatchItem item = new MatchItem();
        item.setText(text);
        item.setDimension(dimension);
        return item;
    }

}
