package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 岗位详情（《API接口设计》5.3）。
 *
 * 含完整 `description`（编辑弹窗回填使用）；`analyzed`/`stale` 含义同列表项。
 *
 * 创建（5.1）与更新（5.4）的成功响应 data 均为本结构——创建时恒为
 * `analyzed = false, stale = false`（新 id 不可能已有分析）。
 */
public class JobDetail {

    private Long id;

    private String companyName;

    private String jobName;

    private String description;

    /** 投递网页链接（M19 新增）：编辑弹窗回填用 */
    private String applyUrl;

    /** 求职进度（M21）：列表行内切换后由响应回显 */
    private String progressStatus;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private boolean analyzed;

    private boolean stale;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

    public String getProgressStatus() {
        return progressStatus;
    }

    public void setProgressStatus(String progressStatus) {
        this.progressStatus = progressStatus;
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

    public boolean isAnalyzed() {
        return analyzed;
    }

    public void setAnalyzed(boolean analyzed) {
        this.analyzed = analyzed;
    }

    public boolean isStale() {
        return stale;
    }

    public void setStale(boolean stale) {
        this.stale = stale;
    }

}
