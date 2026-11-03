package com.careerpilot.dto;

import java.math.BigDecimal;

/**
 * 面试报告中的逐题明细（《API接口设计》§7.4）。
 *
 * 与详情的题目项（{@link InterviewQuestionView}）的差异：不含 `referenceAnswer` /
 * `suggestions`（报告页不展示参考答案），`answer` / `analysis` 供「查看回答」展开
 * （V2.1，R15）；未作答题目两者均为 null。
 */
public class ReportQuestion {

    private Long questionId;

    private String question;

    private String questionCategory;

    private BigDecimal score;

    /** 是否已作答（报告页据此显示「未作答」与「去作答」入口） */
    private boolean answered;

    private String answer;

    private String analysis;

    public Long getQuestionId() {
        return questionId;
    }

    public void setQuestionId(Long questionId) {
        this.questionId = questionId;
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

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public boolean isAnswered() {
        return answered;
    }

    public void setAnswered(boolean answered) {
        this.answered = answered;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

}
