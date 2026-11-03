package com.careerpilot.entity;

import java.time.LocalDateTime;
import java.util.Set;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 目标岗位实体，对应《数据库设计》中的 job。
 *
 * 逻辑删除约定（《数据库设计》5.4，R07）：**手写 status 条件，不用 @TableLogic**——
 * 查询自行附加 {@code status = 1}、删除写 {@code status = 0}。
 *
 * 与简历的区别：岗位是**手输文本**（description），没有文件；且**支持更新**——
 * 描述变更后其 SHA-256 与最新成功分析的 source_hash 不再一致，即分析「已过期」（stale），
 * 这是本项目唯一存在分析过期场景的资源（《API接口设计》5.2）。
 *
 * <p>两列状态务必区分：`status` 是**逻辑删除标记**（1 有效 / 0 已删除，不向前端暴露）；
 * `progressStatus` 是**求职进度**（《数据库设计》§5.18，M21），由用户手动切换。
 */
@TableName("job")
public class Job {

    public static final int STATUS_ACTIVE = 1;

    public static final int STATUS_DELETED = 0;

    /** 求职进度：待投递（新建岗位的默认值） */
    public static final String PROGRESS_PENDING = "PENDING";

    /** 求职进度：已投递 */
    public static final String PROGRESS_APPLIED = "APPLIED";

    /** 求职进度：笔试 */
    public static final String PROGRESS_WRITTEN = "WRITTEN";

    /** 求职进度：一面 */
    public static final String PROGRESS_INTERVIEW_1 = "INTERVIEW_1";

    /** 求职进度：二面 */
    public static final String PROGRESS_INTERVIEW_2 = "INTERVIEW_2";

    /** 求职进度：HR 面 */
    public static final String PROGRESS_HR = "HR";

    /** 求职进度：Offer */
    public static final String PROGRESS_OFFER = "OFFER";

    /** 求职进度：已拒绝 */
    public static final String PROGRESS_REJECTED = "REJECTED";

    /** 全部合法进度值（写路径白名单；不设状态机，用户可任意切换/回退） */
    private static final Set<String> PROGRESS_STATUSES = Set.of(
            PROGRESS_PENDING, PROGRESS_APPLIED, PROGRESS_WRITTEN,
            PROGRESS_INTERVIEW_1, PROGRESS_INTERVIEW_2, PROGRESS_HR,
            PROGRESS_OFFER, PROGRESS_REJECTED);

    public static boolean isValidProgressStatus(String value) {
        return value != null && PROGRESS_STATUSES.contains(value);
    }

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String companyName;

    private String jobName;

    /** 岗位职责与任职要求的完整文本（粘贴 JD 原文，≤ 10000 字符） */
    private String description;

    /** 投递网页链接（M19 新增，选填；http/https，≤ 500 字符，由用户填写） */
    private String applyUrl;

    /** 求职进度（M21）：PENDING/APPLIED/WRITTEN/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED */
    private String progressStatus;

    /** 1 有效，0 已删除（逻辑删除标记，与 progressStatus 无关） */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
