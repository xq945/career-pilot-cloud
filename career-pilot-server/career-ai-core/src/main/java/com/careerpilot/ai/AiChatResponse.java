package com.careerpilot.ai;

/**
 * AI 服务返回的模型原文与元数据（不含业务解析结果）。
 */
public record AiChatResponse(
        String content,
        String modelName,
        String providerKey,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens) {
}
