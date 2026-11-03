package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 会话列表项（《API接口设计》§8.2）。
 *
 * <p>{@code messageCount} 含用户、助手与工具三类消息，供列表"时间 · 消息数"展示。
 */
public class AgentSessionView {

    private String sessionId;

    private String title;

    private Integer messageCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Integer getMessageCount() {
        return messageCount;
    }

    public void setMessageCount(Integer messageCount) {
        this.messageCount = messageCount;
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
