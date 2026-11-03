package com.careerpilot.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 创建简历定向优化的请求（《API接口设计》§6A.1）：{@code {resumeId, jobId}}。
 *
 * <p>归属校验在服务层完成：简历或岗位不存在/非本人一律 404（不区分二者）；
 * 任一方缺少分析结果或岗位分析已过期 → 400（**不自动补齐**，与 §6.1 创建匹配的 REST 语义一致）。
 *
 * <p>「手动输入 JD」由前端编排：先 {@code POST /api/jobs} 与
 * {@code POST /api/jobs/{id}/analyze} 建岗位并分析，再携带 jobId 调用本接口——
 * 后端不提供"临时 JD"入参（避免单请求叠加多次模型调用导致超时不可控）。
 */
public class ResumeOptimizationCreateRequest {

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
