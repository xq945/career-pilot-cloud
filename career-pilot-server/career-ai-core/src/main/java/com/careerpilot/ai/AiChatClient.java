package com.careerpilot.ai;

/**
 * 模型调用代理客户端：单体在 remote 模式下通过它请求 career-ai。
 */
public interface AiChatClient {

    AiChatResponse chat(AiChatRequest request);
}
