package com.careerpilot.ai;

/**
 * 业务服务 → AI 服务的模型调用代理请求（阶段 5）。
 *
 * <p>只代理"一次模型调用"，不代理 Prompt 组装、结构化输出解析与业务清洗——
 * 这些仍留在各业务服务，AI 服务只负责解析 Provider、调用模型并回传原文与元数据。
 */
public record AiChatRequest(
        String scene,
        Long userId,
        String providerKey,
        String systemPrompt,
        String userPrompt) {
}
