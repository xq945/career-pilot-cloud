package com.careerpilot.interview.mq;

import java.math.BigDecimal;

/**
 * interview.completed 消息契约（《微服务改造方案》§2.7.3）。
 *
 * <p>payload 只含 ID 与标量；消费方按 sessionId 回查题目与评价。
 */
public record InterviewCompletedEvent(
        String messageId,
        String eventType,
        String occurredAt,
        Long userId,
        Long aggregateId,
        String aggregateType,
        Integer version,
        String traceId,
        Payload payload) {

    /** 事件载荷：会话 ID 与总平均分 */
    public record Payload(Long sessionId, BigDecimal totalScore) {
    }
}
