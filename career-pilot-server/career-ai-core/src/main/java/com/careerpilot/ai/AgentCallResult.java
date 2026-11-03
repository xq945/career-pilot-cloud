package com.careerpilot.ai;

/**
 * 智能体对话调用的结果与元数据（{@link AiCallTemplate#callWithTools} 的返回值）。
 *
 * <p>与 {@link AiCallResult} 对应，但携带的是**自由文本**（对话回复）而非结构化输出；
 * token 字段可能为 null（上游未返回 usage）。
 */
public record AgentCallResult(
        String message,
        String modelName,
        long elapsedMillis,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
