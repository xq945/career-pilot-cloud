package com.careerpilot.ai;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * career-ai 的内部模型调用端点（仅 remote 模式的单体使用）。
 */
@FeignClient(name = "career-ai", path = "/internal/ai")
public interface AiFeignClient {

    @PostMapping("/chat")
    AiChatResponse chat(@RequestBody AiChatRequest request);
}
