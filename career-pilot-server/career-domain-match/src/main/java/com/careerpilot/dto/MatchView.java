package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 匹配记录列表行（《API接口设计》6.2）。
 *
 * <p>资源名称与版本字段由关联查询得到，**不得附加 {@code status = 1} 过滤**：
 * 源资源逻辑删除后名称仍要正常显示（资源是否已删除由前端探测详情接口 404 体现，
 * 本接口不返回资源状态字段）。
 */
public class MatchView {

    private Long id;

    private Long resumeId;

    private String resumeName;

    private Integer resumeVersionNo;

    private Long jobId;

    private String jobName;

    private String companyName;

    private Integer jobVersionNo;

    private double matchScore;

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

    public Integer getResumeVersionNo() {
        return resumeVersionNo;
    }

    public void setResumeVersionNo(Integer resumeVersionNo) {
        this.resumeVersionNo = resumeVersionNo;
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

    public Integer getJobVersionNo() {
        return jobVersionNo;
    }

    public void setJobVersionNo(Integer jobVersionNo) {
        this.jobVersionNo = jobVersionNo;
    }

    public double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(double matchScore) {
        this.matchScore = matchScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
