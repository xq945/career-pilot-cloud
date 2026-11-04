package com.careerpilot.interview.mq;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.entity.OutboxEvent;
import com.careerpilot.mapper.OutboxEventMapper;

import tools.jackson.databind.ObjectMapper;

/**
 * 本地消息表投递器（阶段 8）：把 {@code outbox_event} 中的 PENDING 事件发到 RabbitMQ。
 *
 * <p>多实例安全：发送前用条件更新把行从 PENDING 原子推进到 SENDING（只有一个实例成功）；
 * 领取后进程崩溃则行停在 SENDING，超过 {@code sending-timeout} 由本调度器回收为 PENDING 重投。
 * 重复投递由消费端幂等（messageId）兜底，因此"至少一次"语义成立。
 */
@Component
@ConditionalOnProperty(prefix = "app.interview.summary", name = "async-enabled",
        havingValue = "true")
public class InterviewOutboxDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InterviewOutboxDispatcher.class);

    /** 单轮投递上限 */
    private static final int BATCH_SIZE = 20;

    /** 超过该次数的投递失败标记 FAILED，等待人工处理 */
    private static final int MAX_RETRY = 10;

    /** SENDING 超时（投递中实例崩溃） */
    private static final int SENDING_TIMEOUT_MINUTES = 2;

    private final OutboxEventMapper outboxEventMapper;

    private final RabbitTemplate rabbitTemplate;

    private final ObjectMapper objectMapper;

    @Value("${app.interview.mq.exchange:cp.dev.topic}")
    private String exchange;

    @Value("${app.interview.mq.routing-key:interview.completed}")
    private String routingKey;

    public InterviewOutboxDispatcher(OutboxEventMapper outboxEventMapper,
                                     RabbitTemplate rabbitTemplate,
                                     ObjectMapper objectMapper) {
        this.outboxEventMapper = outboxEventMapper;
        this.rabbitTemplate = rabbitTemplate;
        this.objectMapper = objectMapper;
    }

    @Scheduled(fixedDelayString = "${app.interview.outbox.scan-interval:10s}")
    public void dispatchPending() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        recoverStaleSending(now);
        List<OutboxEvent> pending = outboxEventMapper.selectList(
                Wrappers.<OutboxEvent>lambdaQuery()
                        .eq(OutboxEvent::getStatus, OutboxEvent.STATUS_PENDING)
                        .orderByAsc(OutboxEvent::getId)
                        .last("LIMIT " + BATCH_SIZE));
        int sent = 0;
        for (OutboxEvent event : pending) {
            if (claim(event.getId(), now)) {
                if (send(event)) {
                    markSent(event.getId(), now);
                    sent++;
                }
                else {
                    markRetry(event, now);
                }
            }
        }
        if (sent > 0) {
            log.info("outbox 投递完成：本轮 {} 条", sent);
        }
    }

    /** SENDING 超时（领取后进程崩溃）回收为 PENDING */
    private void recoverStaleSending(LocalDateTime now) {
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getStatus, OutboxEvent.STATUS_SENDING)
                .lt(OutboxEvent::getUpdatedAt, now.minusMinutes(SENDING_TIMEOUT_MINUTES))
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_PENDING)
                .set(OutboxEvent::getUpdatedAt, now));
    }

    /** 原子领取：只有 PENDING → SENDING 成功的实例负责发送 */
    private boolean claim(Long id, LocalDateTime now) {
        int rows = outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, id)
                .eq(OutboxEvent::getStatus, OutboxEvent.STATUS_PENDING)
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_SENDING)
                .set(OutboxEvent::getUpdatedAt, now));
        return rows > 0;
    }

    private boolean send(OutboxEvent event) {
        try {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("messageId", event.getEventId());
            envelope.put("eventType", event.getEventType());
            envelope.put("occurredAt", OffsetDateTime.now().toString());
            envelope.put("userId", event.getUserId());
            envelope.put("aggregateId", event.getAggregateId());
            envelope.put("aggregateType", event.getAggregateType());
            envelope.put("version", 1);
            envelope.put("traceId", event.getEventId());
            envelope.put("payload", objectMapper.readTree(event.getPayload()));
            String body = objectMapper.writeValueAsString(envelope);
            rabbitTemplate.convertAndSend(exchange, routingKey, body, message -> {
                message.getMessageProperties().setMessageId(event.getEventId());
                message.getMessageProperties().setContentType("application/json");
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            return true;
        }
        catch (RuntimeException ex) {
            log.warn("outbox 投递失败（下一轮重试）：eventId={} 异常={}",
                    event.getEventId(), ex.getClass().getSimpleName());
            return false;
        }
    }

    private void markSent(Long id, LocalDateTime now) {
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, id)
                .set(OutboxEvent::getStatus, OutboxEvent.STATUS_SENT)
                .set(OutboxEvent::getSentAt, now)
                .set(OutboxEvent::getUpdatedAt, now));
    }

    private void markRetry(OutboxEvent event, LocalDateTime now) {
        int retry = event.getRetryCount() == null ? 1 : event.getRetryCount() + 1;
        outboxEventMapper.update(null, Wrappers.<OutboxEvent>lambdaUpdate()
                .eq(OutboxEvent::getId, event.getId())
                .set(OutboxEvent::getStatus,
                        retry >= MAX_RETRY ? OutboxEvent.STATUS_FAILED : OutboxEvent.STATUS_PENDING)
                .set(OutboxEvent::getRetryCount, retry)
                .set(OutboxEvent::getUpdatedAt, now));
    }
}
