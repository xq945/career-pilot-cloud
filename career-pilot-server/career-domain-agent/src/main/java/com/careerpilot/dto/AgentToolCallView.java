package com.careerpilot.dto;

/**
 * 工具调用摘要（《API接口设计》§8.1 响应的 toolCalls 元素、§8.3 消息的 status 字段）。
 *
 * <p>只含工具名与执行状态：工具的原始入参与结果可能包含大段简历文本，不对外暴露。
 */
public class AgentToolCallView {

    private String toolName;

    /** SUCCESS / FAILED */
    private String status;

    public AgentToolCallView() {
    }

    public AgentToolCallView(String toolName, String status) {
        this.toolName = toolName;
        this.status = status;
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

}
