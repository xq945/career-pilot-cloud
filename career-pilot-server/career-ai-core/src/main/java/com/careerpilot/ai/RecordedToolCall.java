package com.careerpilot.ai;

import java.time.LocalDateTime;

/**
 * 一次**实际执行过**的工具调用的记录（《Agent设计》§7.5：应用层包装记录，不依赖模型自报）。
 *
 * <p>用途三处：整轮落库（{@code agent_chat_message} 的 tool 行）、接口响应的
 * {@code toolCalls} 摘要、越限时的部分结果文案（内容来自本记录，而非模型自报）。
 *
 * <p>注意：{@code resultJson} 不在此记录——落库只写精简载荷
 * {@code {status, elapsedMillis, summary}}（大段工具结果不回传、不落库，见 §8.3）。
 */
public record RecordedToolCall(
        String toolName,
        String argumentsJson,
        String status,
        String summary,
        long elapsedMillis,
        LocalDateTime startedAt) {

    public static final String STATUS_SUCCESS = "SUCCESS";

    public static final String STATUS_FAILED = "FAILED";

}
