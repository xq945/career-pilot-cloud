package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 会话中的一条消息（《API接口设计》§8.3）。
 *
 * <p>{@code role} 三类：user / assistant / tool。工具消息**只返回工具名与状态**
 * （{@code toolName}/{@code status}），不返回原始入参与结果；{@code status} 取自
 * 落库时的 tool_result 载荷。
 */
public class AgentMessageView {

    private String role;

    private String content;

    /** 仅 role=tool 时返回 */
    private String toolName;

    /** 仅 role=tool 时返回（SUCCESS / FAILED） */
    private String status;

    private LocalDateTime createdAt;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
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

}
