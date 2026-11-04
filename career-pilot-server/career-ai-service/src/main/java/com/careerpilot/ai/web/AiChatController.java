package com.careerpilot.ai.web;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.ai.AiCallTemplate;
import com.careerpilot.ai.AiChatRequest;
import com.careerpilot.ai.AiChatResponse;

/**
 * 业务服务的模型调用代理入口（阶段 5）。
 *
 * <p>只做一次模型调用并回传原文与元数据；Prompt、结构化解析与业务清洗仍在调用方。
 * 工具调用（Agent）不经过本端点。
 */
@RestController
@RequestMapping("/internal/ai")
public class AiChatController {

    private final AiCallTemplate aiCallTemplate;

    public AiChatController(AiCallTemplate aiCallTemplate) {
        this.aiCallTemplate = aiCallTemplate;
    }

    @PostMapping("/chat")
    public AiChatResponse chat(@RequestBody AiChatRequest request) {
        return aiCallTemplate.complete(request.scene(), request.providerKey(),
                request.systemPrompt(), request.userPrompt());
    }
}
