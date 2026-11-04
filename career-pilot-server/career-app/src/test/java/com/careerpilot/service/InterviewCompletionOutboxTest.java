package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.OutboxEvent;
import com.careerpilot.mapper.OutboxEventMapper;

import tools.jackson.databind.json.JsonMapper;

/**
 * 阶段 8：完成事件写入本地消息表（与提交回答同事务）。
 */
class InterviewCompletionOutboxTest {

    @Test
    void enqueue_writesPendingEventWithIdsOnlyPayload() {
        OutboxEventMapper mapper = mock(OutboxEventMapper.class);
        InterviewCompletionOutbox outbox = new InterviewCompletionOutbox(
                mapper, JsonMapper.builder().build());
        InterviewSession session = new InterviewSession();
        session.setId(10L);
        session.setUserId(1L);

        outbox.enqueue(session, new BigDecimal("85.00"));

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(mapper).insert(captor.capture());
        OutboxEvent event = captor.getValue();
        assertEquals("interview.completed", event.getEventType());
        assertEquals("INTERVIEW_SESSION", event.getAggregateType());
        assertEquals(10L, event.getAggregateId());
        assertEquals(1L, event.getUserId());
        assertEquals(OutboxEvent.STATUS_PENDING, event.getStatus());
        assertEquals(0, event.getRetryCount());
        // payload 只放 ID 与标量（不放题目/评价正文）
        assertTrue(event.getPayload().contains("\"sessionId\":10"));
        assertTrue(event.getPayload().contains("\"totalScore\":85.00"));
    }
}
