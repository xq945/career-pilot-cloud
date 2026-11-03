package com.careerpilot.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 创建匹配分析的请求（《API接口设计》6.1）：{@code {resumeId, jobId}}。
 *
 * 归属校验在服务层完成：简历或岗位不存在/非本人一律 404（不区分二者）；
 * 任一方缺少分析结果或岗位分析已过期 → 400（不自动补齐，与 Agent 工具入口的差异见《系统架构设计》6.2）。
 */
public class MatchCreateRequest {

    @NotNull(message = "resumeId 不能为空")
    private Long resumeId;

    @NotNull(message = "jobId 不能为空")
    private Long jobId;

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

}
