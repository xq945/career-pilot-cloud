package com.careerpilot.dto;

import java.util.List;

/**
 * 会话消息回看（《API接口设计》§8.3）：会话标题 + 全部消息（按 created_at 升序）。
 *
 * <p>第一版不分页："会话消息数量由工具调用上限自然约束"（§8.3）。
 */
public class AgentSessionDetail {

    private String sessionId;

    private String title;

    private List<AgentMessageView> messages;

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

    public List<AgentMessageView> getMessages() {
        return messages;
    }

    public void setMessages(List<AgentMessageView> messages) {
        this.messages = messages;
    }

}
