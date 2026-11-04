package com.careerpilot.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.OutboxEvent;
import com.careerpilot.mapper.OutboxEventMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * 「面试完成」事件的本地消息表写入（阶段 8）。
 *
 * <p>由 {@link InterviewTxService} 在**提交回答的同一事务内**调用（最后一题且开启异步时），
 * 保证"会话完成"与"事件已记录"原子生效；投递由 career-interview 的调度器异步完成。
 */
@Component
public class InterviewCompletionOutbox {

    /** 事件类型（RabbitMQ routing key 同值） */
    public static final String EVENT_TYPE = "interview.completed";

    /** 聚合类型 */
    public static final String AGGREGATE_TYPE = "INTERVIEW_SESSION";

    private final OutboxEventMapper outboxEventMapper;

    private final ObjectMapper objectMapper;

    public InterviewCompletionOutbox(OutboxEventMapper outboxEventMapper, ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.objectMapper = objectMapper;
    }

    /**
     * 写入一条待投递事件。载荷只含会话 ID 与总分（消费方按 ID 回查，§2.7.3）。
     */
    public void enqueue(InterviewSession session, BigDecimal totalScore) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sessionId", session.getId());
        payload.put("totalScore", totalScore);

        LocalDateTime now = LocalDateTime.now().withNano(0);
        OutboxEvent event = new OutboxEvent();
        event.setEventId(UUID.randomUUID().toString());
        event.setEventType(EVENT_TYPE);
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(session.getId());
        event.setUserId(session.getUserId());
        event.setPayload(objectMapper.writeValueAsString(payload));
        event.setStatus(OutboxEvent.STATUS_PENDING);
        event.setRetryCount(0);
        event.setCreatedAt(now);
        event.setUpdatedAt(now);
        outboxEventMapper.insert(event);
    }
}
