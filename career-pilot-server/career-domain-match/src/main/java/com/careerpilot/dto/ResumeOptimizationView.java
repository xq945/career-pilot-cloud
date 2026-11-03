package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 优化记录列表行（《API接口设计》§6A.2）。
 *
 * <p>资源名称由关联查询得到，**不得附加 {@code status = 1} 过滤**：
 * 源资源逻辑删除后名称仍要正常显示（同 §6.2 的匹配列表口径）。
 *
 * <p>两个分数字段均用包装类型 {@link Double}：`scoreAfter` 未评估时为 null；
 * `matchScore` 在任务未完成（M16 异步化后的 PENDING/PROCESSING/FAILED）时为 null——
 * 前端按状态标签渲染，显示「待评估」而不是 0 分。
 */
public class ResumeOptimizationView {

    private Long id;

    private Long resumeId;

    private String resumeName;

    private Long jobId;

    private String jobName;

    private String companyName;

    private Integer versionNo;

    /** 优化前匹配总分（由规则计算，来自优化前那条 match_record）；M16 起为包装类型：任务未完成时为 null */
    private Double matchScore;

    /** 优化后匹配总分；null 表示尚未评估 */
    private Double scoreAfter;

    private String status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public Double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(Double matchScore) {
        this.matchScore = matchScore;
    }

    public Double getScoreAfter() {
        return scoreAfter;
    }

    public void setScoreAfter(Double scoreAfter) {
        this.scoreAfter = scoreAfter;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
