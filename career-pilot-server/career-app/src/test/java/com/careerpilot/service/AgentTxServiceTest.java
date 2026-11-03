package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.careerpilot.ai.RecordedToolCall;
import com.careerpilot.entity.AgentChatMessage;
import com.careerpilot.entity.AgentSession;
import com.careerpilot.mapper.AgentChatMessageMapper;
import com.careerpilot.mapper.AgentSessionMapper;

/**
 * 整轮落库的事务写路径（《API接口设计》§8.1/A26、G-8）：
 * 写入顺序、工具行字段、同一事务内显式刷新会话 updated_at。
 */
class AgentTxServiceTest {

    private AgentSessionMapper sessionMapper;

    private AgentChatMessageMapper messageMapper;

    private AgentTxService service;

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AgentSessionMapper.class);
        messageMapper = mock(AgentChatMessageMapper.class);
        service = new AgentTxService(sessionMapper, messageMapper);
    }

    @Test
    void saveTurn_firstTurn_insertsSessionThenThreeMessages_thenRefreshesUpdatedAt() {
        AgentSession session = new AgentSession();
        session.setUserId(7L);
        session.setSessionId("sid-1");
        session.setTitle("帮我分析简历");
        LocalDateTime userAt = LocalDateTime.of(2026, 10, 10, 12, 0, 0);
        LocalDateTime assistantAt = userAt.plusSeconds(30);
        List<RecordedToolCall> calls = List.of(new RecordedToolCall("analyzeResume",
                "{\"resumeId\":12}", "SUCCESS", "简历分析完成", 1234L, userAt.plusSeconds(5)));

        service.saveTurn(session, true, 7L, "帮我分析简历", calls, "分析结果如下", userAt, assistantAt);

        InOrder inOrder = inOrder(sessionMapper, messageMapper);
        inOrder.verify(sessionMapper).insert(session);
        inOrder.verify(messageMapper, times(3)).insert(any(AgentChatMessage.class));
        inOrder.verify(sessionMapper).updateById(any(AgentSession.class));

        ArgumentCaptor<AgentChatMessage> messages = ArgumentCaptor.forClass(AgentChatMessage.class);
        verify(messageMapper, times(3)).insert(messages.capture());
        List<AgentChatMessage> saved = messages.getAllValues();
        assertEquals(AgentChatMessage.ROLE_USER, saved.get(0).getRole());
        assertEquals("帮我分析简历", saved.get(0).getContent());
        assertEquals(userAt, saved.get(0).getCreatedAt());
        assertEquals(AgentChatMessage.ROLE_TOOL, saved.get(1).getRole());
        assertEquals("analyzeResume", saved.get(1).getToolName());
        assertEquals("SUCCESS", saved.get(1).getToolResult().getStatus());
        assertEquals(1234L, saved.get(1).getToolResult().getElapsedMillis());
        assertEquals("简历分析完成", saved.get(1).getToolResult().getSummary());
        assertEquals(12, ((Number) saved.get(1).getToolArguments().get("resumeId")).intValue());
        assertEquals(AgentChatMessage.ROLE_ASSISTANT, saved.get(2).getRole());
        assertEquals("分析结果如下", saved.get(2).getContent());
    }

    @Test
    void saveTurn_existingSession_doesNotInsertSessionButRefreshesUpdatedAt() {
        AgentSession session = new AgentSession();
        session.setId(5L);
        session.setSessionId("sid-1");
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 13, 0, 0);

        service.saveTurn(session, false, 7L, "继续", List.of(), "好的", now, now.plusSeconds(10));

        verify(sessionMapper, never()).insert(isA(AgentSession.class));
        ArgumentCaptor<AgentSession> patch = ArgumentCaptor.forClass(AgentSession.class);
        verify(sessionMapper).updateById(patch.capture());
        // G-8：会话列表排序依据在应用层显式刷新
        assertEquals(5L, patch.getValue().getId());
        assertEquals(now.plusSeconds(10), patch.getValue().getUpdatedAt());
    }

    @Test
    void saveTurn_nonJsonArguments_keepsTruncatedRawText() {
        AgentSession session = new AgentSession();
        session.setId(5L);
        session.setSessionId("sid-1");
        LocalDateTime now = LocalDateTime.of(2026, 10, 10, 13, 0, 0);
        // 工具入参理论上是 JSON；非 JSON 时以 _raw 保留截断原文，不使整轮失败
        List<RecordedToolCall> calls = List.of(new RecordedToolCall("analyzeResume",
                "不是JSON", "SUCCESS", "摘要", 5L, now));

        service.saveTurn(session, false, 7L, "hi", calls, "ok", now, now);

        ArgumentCaptor<AgentChatMessage> messages = ArgumentCaptor.forClass(AgentChatMessage.class);
        verify(messageMapper, times(3)).insert(messages.capture());
        assertEquals("不是JSON", messages.getAllValues().get(1).getToolArguments().get("_raw"));
    }

}
