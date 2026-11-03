package com.careerpilot.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建面试会话的响应（《API接口设计》§7.1）。
 *
 * `questionCount`（计划，落库）与 `generatedCount`（实际，= 题目行数，**不落库**）
 * 是两个来源，装配时不要混用（G-3）。
 */
public class InterviewCreateResponse {

    private Long id;

    private String title;

    private String questionType;

    /** 请求中的**计划**题目数（interview_session.question_count） */
    private Integer questionCount;

    /** **实际**生成并通过校验的题目数（= questions 的行数） */
    private Integer generatedCount;

    private Integer status;

    private LocalDateTime createdAt;

    private List<InterviewQuestionItem> questions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public Integer getGeneratedCount() {
        return generatedCount;
    }

    public void setGeneratedCount(Integer generatedCount) {
        this.generatedCount = generatedCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<InterviewQuestionItem> getQuestions() {
        return questions;
    }

    public void setQuestions(List<InterviewQuestionItem> questions) {
        this.questions = questions;
    }

}
