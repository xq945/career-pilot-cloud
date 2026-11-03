package com.careerpilot.dto;

/**
 * 创建面试响应中的题目项（《API接口设计》§7.1）——只含题干与定位信息，
 * 不含回答/评价（会话尚未开始作答）。完整题目结构见 {@link InterviewQuestionView}。
 */
public class InterviewQuestionItem {

    private Long id;

    private String question;

    /** 六枚举之一；模型未给出或越界时为 null（清洗时置 null） */
    private String questionCategory;

    private Integer sortOrder;

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

}
