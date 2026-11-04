package com.careerpilot.ai;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * remote 模式的模型代理实现：单体把非工具调用交给 career-ai。
 */
@Service
@ConditionalOnProperty(prefix = "app.ai", name = "mode", havingValue = "remote")
public class RemoteAiChatClient implements AiChatClient {

    private final AiFeignClient feignClient;

    public RemoteAiChatClient(AiFeignClient feignClient) {
        this.feignClient = feignClient;
    }

    @Override
    public AiChatResponse chat(AiChatRequest request) {
        return feignClient.chat(request);
    }
}
