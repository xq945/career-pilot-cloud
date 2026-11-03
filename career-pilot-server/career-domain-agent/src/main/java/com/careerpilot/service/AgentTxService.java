package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.careerpilot.ai.RecordedToolCall;
import com.careerpilot.dto.AgentToolResultPayload;
import com.careerpilot.entity.AgentChatMessage;
import com.careerpilot.entity.AgentSession;
import com.careerpilot.mapper.AgentChatMessageMapper;
import com.careerpilot.mapper.AgentSessionMapper;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * 智能体对话的**写路径**（事务边界集中地，沿用 InterviewTxService 的范式）：
 * 独立成 bean 是刻意的——{@code @Transactional} 经 Spring 代理生效，若把写方法放在调用方
 * 同类中会因自调用失效；同时把事务范围限定在"落库段"，**模型调用一律发生在事务之外**。
 *
 * <p>「整轮成功才落库」（《API接口设计》§8.1/A26）：用户消息、工具调用记录、助手回复与
 * 会话元数据（首轮时）在**同一事务**写入；502 时本方法根本不会被调用（抛出先于写入）。
 * truncated=true 属成功路径，同样走本方法照常落库（R05）。
 *
 * <p>G-8：写入消息后必须在同一事务内**显式更新** {@code agent_session.updated_at}
 * （{@code ON UPDATE CURRENT_TIMESTAMP} 不因插入子表行而触发，否则会话列表排序长期不变）。
 *
 * <p>时间统一裁到秒（DATETIME(0) 对齐，防跨接口 1 秒抖动；MP 不回读库默认值）。
 */
@Service
public class AgentTxService {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** 参数原文的长度上限（非 JSON 参数兜底保留时的截断） */
    private static final int RAW_ARGUMENTS_LIMIT = 500;

    private final AgentSessionMapper agentSessionMapper;

    private final AgentChatMessageMapper agentChatMessageMapper;

    public AgentTxService(AgentSessionMapper agentSessionMapper,
                          AgentChatMessageMapper agentChatMessageMapper) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentChatMessageMapper = agentChatMessageMapper;
    }

    /**
     * 整轮落库。
     *
     * @param session           会话实体；首轮时 id 为空（本方法插入并回填），否则为已存在会话
     * @param firstTurn         是否首轮（决定是否插入会话元数据）
     * @param userMessage       本轮用户消息原文（已 trim）
     * @param toolCalls         本轮实际执行的工具调用（被拒绝的调用不在其中）
     * @param assistantMessage  助手回复
     * @param userMessageAt     用户消息时间（本轮开始）
     * @param assistantMessageAt 助手回复时间（模型返回后）
     */
    @Transactional
    public void saveTurn(AgentSession session, boolean firstTurn, Long userId,
                         String userMessage, List<RecordedToolCall> toolCalls,
                         String assistantMessage, LocalDateTime userMessageAt,
                         LocalDateTime assistantMessageAt) {
        if (firstTurn) {
            agentSessionMapper.insert(session);
        }

        insertTextMessage(userId, session.getSessionId(),
                AgentChatMessage.ROLE_USER, userMessage, userMessageAt);

        for (RecordedToolCall call : toolCalls) {
            AgentChatMessage tool = new AgentChatMessage();
            tool.setUserId(userId);
            tool.setSessionId(session.getSessionId());
            tool.setRole(AgentChatMessage.ROLE_TOOL);
            tool.setToolName(call.toolName());
            tool.setToolArguments(parseArguments(call.argumentsJson()));
            // 落库只留精简载荷（§8.3：工具原始入参与结果不对外返回，落库同理）
            tool.setToolResult(new AgentToolResultPayload(
                    call.status(), call.elapsedMillis(), call.summary()));
            tool.setCreatedAt(call.startedAt());
            agentChatMessageMapper.insert(tool);
        }

        insertTextMessage(userId, session.getSessionId(),
                AgentChatMessage.ROLE_ASSISTANT, assistantMessage, assistantMessageAt);

        // G-8：同一事务内显式刷新会话 updated_at（会话列表排序依据）
        AgentSession patch = new AgentSession();
        patch.setId(session.getId());
        patch.setUpdatedAt(assistantMessageAt);
        agentSessionMapper.updateById(patch);
    }

    private void insertTextMessage(Long userId, String sessionId, String role,
                                   String content, LocalDateTime createdAt) {
        AgentChatMessage message = new AgentChatMessage();
        message.setUserId(userId);
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        message.setCreatedAt(createdAt);
        agentChatMessageMapper.insert(message);
    }

    /** 工具入参 JSON → Map（JSON 列）；解析失败时以 {@code _raw} 保留截断原文，不使整轮失败 */
    private static Map<String, Object> parseArguments(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(argumentsJson, Map.class);
        }
        catch (RuntimeException ex) {
            Map<String, Object> raw = new LinkedHashMap<>();
            String flat = argumentsJson.replaceAll("\\s+", " ").trim();
            raw.put("_raw", flat.length() > RAW_ARGUMENTS_LIMIT
                    ? flat.substring(0, RAW_ARGUMENTS_LIMIT) + "…" : flat);
            return raw;
        }
    }

}
