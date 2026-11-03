package com.careerpilot.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 提交回答的成功响应（《API接口设计》§7.2）：本次评价结果。
 *
 * `score` 为 0–100、已钳制并保留两位小数（`setScale(2, HALF_UP)`）；
 * 四字段与落库的 interview_question 评价列**逐字段一致**（落库即响应）。
 * 参考答案/点评/建议在**评价时**产生（出题不生成，见 §7.2/§7.3）。
 */
public class AnswerEvaluationResponse {

    private Long questionId;

    private BigDecimal score;

    private String analysis;

    private String referenceAnswer;

    private List<String> suggestions;

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
    }

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
