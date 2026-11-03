package com.careerpilot.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * 面试详情中的题目项（《API接口设计》§7.3）。
 *
 * 可空规则：未作答时 `answer`、`score`、`analysis`、`referenceAnswer`、`suggestions`
 * **均为 null**——回答与评价字段同时写入、同时为空（由落库设计保证，不存在中间状态）；
 * `questionCategory` 可为 null（模型未给出类别时）。
 */
public class InterviewQuestionView {

    private Long id;

    private String question;

    private String questionCategory;

    private Integer sortOrder;

    private String answer;

    private BigDecimal score;

    private String analysis;

    private String referenceAnswer;

    private List<String> suggestions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getQuestionCategory() {
        return questionCategory;
    }

    public void setQuestionCategory(String questionCategory) {
        this.questionCategory = questionCategory;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
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
