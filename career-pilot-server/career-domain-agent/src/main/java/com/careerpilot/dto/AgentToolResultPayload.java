package com.careerpilot.dto;

/**
 * tool 消息的落库载荷（agent_chat_message.tool_result 的 JSON 结构）。
 *
 * <p>刻意**不含**入参与完整结果：工具原始内容可能含大段简历文本，
 * 《API接口设计》§8.3 要求工具消息对外只暴露工具名与状态，落库同理只留摘要。
 * 其中 {@code status} 是 §8.3 响应 status 字段的**唯一来源**（表结构没有 status 列）。
 */
public class AgentToolResultPayload {

    /** SUCCESS / FAILED（与 com.careerpilot.ai.RecordedToolCall 的常量一致） */
    private String status;

    /** 工具执行耗时（毫秒；被拒绝而未执行的调用不会产生记录，故必有值） */
    private Long elapsedMillis;

    /** 一句话结果摘要（AgentToolPayloads 信封中的 summary） */
    private String summary;

    public AgentToolResultPayload() {
    }

    public AgentToolResultPayload(String status, Long elapsedMillis, String summary) {
        this.status = status;
        this.elapsedMillis = elapsedMillis;
        this.summary = summary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Long getElapsedMillis() {
        return elapsedMillis;
    }

    public void setElapsedMillis(Long elapsedMillis) {
        this.elapsedMillis = elapsedMillis;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

}
