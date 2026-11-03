package com.careerpilot.dto;

import java.math.BigDecimal;

/**
 * 面试报告的按类别得分（《API接口设计》§7.4）：仅统计**已作答**题目。
 *
 * `category` 为 `questionCategory` 分组键；题目未给出类别时为 **null**
 * （前端显示"未分类"，与总分的统计口径保持一致——总分同样包含这些题目）。
 */
public class CategoryScore {

    private String category;

    /** 组内已作答题目得分的平均值，两位小数 */
    private BigDecimal averageScore;

    /** 组内已作答题目数 */
    private Integer questionCount;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public BigDecimal getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(BigDecimal averageScore) {
        this.averageScore = averageScore;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

}
