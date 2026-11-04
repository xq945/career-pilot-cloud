package com.careerpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.AgentChatRequest;
import com.careerpilot.dto.AgentChatResponse;
import com.careerpilot.dto.AgentSessionDetail;
import com.careerpilot.dto.AgentSessionView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.AgentService;

import jakarta.validation.Valid;

/**
 * 求职智能体接口（《API接口设计》§8.1–§8.3）。
 *
 * <p>Agent 的四个工具（analyzeResume / analyzeJob / matchResumeToJob /
 * generateInterviewQuestions）**没有独立 HTTP 端点、不对前端暴露**，
 * 只能由模型在 {@code POST /api/agent/chat} 中触发（§8 前言/A27）。
 *
 * <p>身份一律从认证上下文获取（{@link CurrentUser#requireId()}），不信任前端传参。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    /**
     * 对话（§8.1）：第一版**非流式**——整体等待后一次性返回；
     * 单次请求受「工具调用 ≤5 次、总时长 ≤120 秒」约束，超限时返回部分结果并置
     * {@code truncated = true}。
     */
    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest request) {
        return ApiResponse.success(agentService.chat(CurrentUser.requireId(), request));
    }

    /** 会话列表（§8.2）：按最近活跃（updated_at）倒序分页，含三类消息的消息数 */
    @GetMapping("/sessions")
    public ApiResponse<PageResult<AgentSessionView>> listSessions(
            @Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(agentService.listSessions(CurrentUser.requireId(), query));
    }

    /**
     * 消息回看（§8.3）：按时间升序、第一版不分页；
     * tool 消息只返回工具名与状态（原始入参与结果可能含大段简历文本，不返回）。
     */
    @GetMapping("/sessions/{sessionId}")
    public ApiResponse<AgentSessionDetail> sessionDetail(@PathVariable String sessionId) {
        return ApiResponse.success(agentService.detail(CurrentUser.requireId(), sessionId));
    }

}
