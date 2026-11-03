package com.careerpilot.dto.ai;

import java.math.BigDecimal;
import java.util.List;

/**
 * 评价场景的模型输出结构（《Agent设计》§4.6「面试评价」行）。
 *
 * 清洗规则：`score` 为 0–100 的数值，**超范围钳制到边界**（并统一两位小数）；
 * `score` 缺失或非数值（反序列化失败）由 {@code AiCallTemplate} 判为 50201；
 * `analysis` / `referenceAnswer` ≤ 2000 字符、`suggestions` ≤ 20 项。
 */
public class InterviewEvaluationAiOutput {

    /** 0–100，超范围钳制（模型给出 150 → 100） */
    private BigDecimal score;

    /** 点评：指出哪里对、哪里缺（≤ 2000 字符） */
    private String analysis;

    /** 参考答案（≤ 2000 字符） */
    private String referenceAnswer;

    /** 改进建议（≤ 20 项） */
    private List<String> suggestions;

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

}
