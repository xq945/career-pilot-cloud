package com.careerpilot.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.entity.InterviewQuestion;

/**
 * 降级总评（M15）：由已落库的逐题点评确定性拼装。三条纪律的护栏测试——
 * **不伪造分数、不新增事实、明确标注来源**（《数据库设计》5.14）。
 */
class InterviewSummaryFallbackTest {

    private final InterviewSummaryFallback fallback = new InterviewSummaryFallback();

    @Test
    @DisplayName("由逐题点评拼装：标注系统汇总、引用总分与题数、强项/薄弱取自两极题点评、建议汇聚自逐题建议")
    void buildsFromQuestionFeedback() {
        List<InterviewQuestion> questions = List.of(
                question(new BigDecimal("90.00"), "回答覆盖了核心概念并给出源码级分析。",
                        List.of("复习 G1 参数调优")),
                question(new BigDecimal("50.00"), "只停留在表面，未涉及并发模型。",
                        List.of("补充并发模型练习")),
                question(new BigDecimal("70.00"), "概念正确，缺少边界条件讨论。", List.of()));

        InterviewSummaryAiOutput output = fallback.build(questions, new BigDecimal("70.00"));

        assertTrue(output.getSummary().contains("系统汇总"), output.getSummary());
        assertTrue(output.getSummary().contains("70.00"), output.getSummary());
        assertTrue(output.getSummary().contains("3"), output.getSummary());
        assertFalse(output.getStrengths().isEmpty());
        assertFalse(output.getWeaknesses().isEmpty());
        assertTrue(output.getStrengths().get(0).contains("源码级分析"),
                "强项应取自最高分题的点评：" + output.getStrengths());
        assertTrue(output.getWeaknesses().get(0).contains("未涉及并发模型"),
                "薄弱应取自最低分题的点评：" + output.getWeaknesses());
        assertTrue(output.getRecommendations().contains("复习 G1 参数调优"));
        assertTrue(output.getRecommendations().contains("补充并发模型练习"));
    }

    @Test
    @DisplayName("不伪造分数：文本中除已落库总分外不出现其他两位小数分数")
    void doesNotInventScores() {
        List<InterviewQuestion> questions = List.of(
                question(new BigDecimal("88.00"), "细节准确。", List.of("多练手写题")));
        InterviewSummaryAiOutput output = fallback.build(questions, new BigDecimal("88.00"));

        String withoutKnownTotal = output.getSummary().replace("88.00", "");
        assertFalse(withoutKnownTotal.matches(".*\\d+\\.\\d{2}.*"),
                "不应出现除总分之外的分数：" + output.getSummary());
    }

    @Test
    @DisplayName("点评与建议缺失时不崩溃：标签为空、建议走兜底句")
    void toleratesMissingFeedback() {
        InterviewQuestion withoutFeedback = question(new BigDecimal("60.00"), null, null);

        InterviewSummaryAiOutput output = fallback.build(List.of(withoutFeedback), new BigDecimal("60.00"));

        assertTrue(output.getStrengths().isEmpty());
        assertTrue(output.getWeaknesses().isEmpty());
        assertEquals(1, output.getRecommendations().size());
        assertTrue(output.getSummary().contains("系统汇总"));
    }

    private static InterviewQuestion question(BigDecimal score, String analysis, List<String> suggestions) {
        InterviewQuestion question = new InterviewQuestion();
        question.setScore(score);
        question.setAnalysis(analysis);
        question.setSuggestions(suggestions);
        return question;
    }

}
