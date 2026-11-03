package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 岗位列表项（《API接口设计》5.2）。
 *
 * **不返回 `description`**（可能长达 10000 字符），需要时由详情接口获取。
 *
 * `analyzed` 与 `stale` 驱动列表的三态标签（未分析 / 已分析 / 分析已过期），
 * 前端无需另发请求判断（《前端原型设计》10.1）：
 * - `analyzed = false` 时 `stale` 恒为 false；
 * - `stale = true` 表示当前描述与最新成功分析的 source_hash 不一致（描述被修改过）。
 */
public class JobView {

    private Long id;

    private String companyName;

    private String jobName;

    /** 投递网页链接（M19 新增）：非空时列表渲染「投递」按钮，为空/缺省则按钮不显示 */
    private String applyUrl;

    /** 求职进度（M21）：PENDING/APPLIED/WRITTEN/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED */
    private String progressStatus;

    private LocalDateTime createdAt;

    /** 描述更新时间（列表展示「描述更新于 …」用） */
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
