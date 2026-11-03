package com.careerpilot.dto;

import java.util.List;

/**
 * 智能体对话响应（《API接口设计》§8.1）。
 *
 * <p>{@code truncated} 为 true 表示达到工具调用次数或总时长上限（前端应提示"回答可能不完整"）；
 * 它**仅为响应字段、不落库**（R05：越限属成功路径，该轮消息照常整轮落库）。
 */
public class AgentChatResponse {

    private String sessionId;

    private String message;

    private boolean truncated;

    /** 本轮实际执行过的工具调用摘要（工具名 + 状态；达到上限后被拒绝的调用不计入） */
    private List<AgentToolCallView> toolCalls;

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

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<AgentToolCallView> getToolCalls() {
        return toolCalls;
    }

    public void setToolCalls(List<AgentToolCallView> toolCalls) {
        this.toolCalls = toolCalls;
    }

}
