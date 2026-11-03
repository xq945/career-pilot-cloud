package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 仪表盘「最近匹配记录」项（《API接口设计》9.1，V2.1 R23）。
 *
 * <p>**固定 8 字段**——较 6.2 的列表项（{@link MatchView}）少
 * {@code resumeVersionNo} / {@code jobVersionNo}，其余字段名保持一致；
 * 刻意不复用 MatchView，避免带出多余字段。
 */
public class DashboardRecentMatch {

    private Long id;

    private Long resumeId;

    /** 内联名称：源资源逻辑删除后仍返回（查询不过滤 status，同 6.2 的可见性规则） */
    private String resumeName;

    private Long jobId;

    private String jobName;

    private String companyName;

    /** 装配时由 BigDecimal 转 double（避免 82.40 这类尾零，同 6.1 的既有口径） */
    private Double matchScore;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public String getResumeName() {
        return resumeName;
    }

    public void setResumeName(String resumeName) {
        this.resumeName = resumeName;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public Double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Double matchScore) {
        this.matchScore = matchScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
