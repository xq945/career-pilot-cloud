package com.careerpilot.dto;

/**
 * 优化任务失败时的一条结构化问题（V1.13，2026-10-21）。
 *
 * <p>来源是 {@code resume_optimization.failure_detail} 中由事实校验器产出的
 * desensitized issue：只含类别、严重度、脱敏描述与变更索引，不含简历正文。
 * 详情页据此展示「校验问题明细」，解决 error_message 只能放前 5 项的问题。
 */
public class OptimizationFailureIssue {

    private String category;

    private String severity;

    private String message;

    private Integer changeIndex;

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Integer getChangeIndex() {
        return changeIndex;
    }

    public void setChangeIndex(Integer changeIndex) {
        this.changeIndex = changeIndex;
    }

}
