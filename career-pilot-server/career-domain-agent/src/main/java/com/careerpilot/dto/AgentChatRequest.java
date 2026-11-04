package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 智能体对话请求体（《API接口设计》§8.1）。
 *
 * <p>{@code sessionId}/{@code resumeId}/{@code jobId} 均可选；**一旦提供，后端必须校验归属**
 * （不存在、非本人或已逻辑删除一律 404，且不得进入模型调用）。
 * 首轮请求省略 sessionId，由服务端生成并返回。
 */
public class AgentChatRequest {

    /** 会话标识；首次对话省略 */
    @Size(max = 64, message = "会话标识长度不能超过 64 个字符")
    private String sessionId;

    @NotBlank(message = "消息不能为空")
    @Size(max = 2000, message = "消息不能超过 2000 个字符")
    private String message;

    /**
     * 阶段 9：单轮标识（可选）。客户端重试同一请求时可复用该值，用于日志关联与工具幂等键；
     * 省略时由服务端生成（UUID 去连字符）。
     */
    @Size(max = 36, message = "单轮标识长度不能超过 36 个字符")
    private String turnId;

    /** 本次对话引用的简历（可选上下文资源） */
    @Positive(message = "简历 ID 必须为正整数")
    private Long resumeId;

    /** 本次对话引用的岗位（可选上下文资源） */
    @Positive(message = "岗位 ID 必须为正整数")
    private Long jobId;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getTurnId() {
        return turnId;
    }

    public void setTurnId(String turnId) {
        this.turnId = turnId;
    }

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
