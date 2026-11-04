package com.careerpilot.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 本地消息表（阶段 8，cp_interview.outbox_event）。
 *
 * <p>业务事务内与面试会话/题目同事务写入，事务提交后由 {@code InterviewOutboxDispatcher}
 * 发送到 RabbitMQ 并标记；避免"数据库已提交、消息未发送"的丢失窗口。
 *
 * <p>{@code payload} 只放 ID 与标量（sessionId、totalScore），不放题目与评价正文——
 * 消费方按 ID 回查（《微服务改造方案》§2.7.3）。
 */
@TableName("outbox_event")
public class OutboxEvent {

    /** 待投递 */
    public static final String STATUS_PENDING = "PENDING";

    /** 已被某个实例领取（投递中；进程崩溃时由调度器回收） */
    public static final String STATUS_SENDING = "SENDING";

    /** 已投递 */
    public static final String STATUS_SENT = "SENT";

    /** 超过重试上限，人工处理 */
    public static final String STATUS_FAILED = "FAILED";

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件唯一标识（UUID），同时作为 MQ messageId 与消费幂等键 */
    private String eventId;

    /** 事件类型，如 interview.completed */
    private String eventType;

    /** 聚合类型，如 INTERVIEW_SESSION */
    private String aggregateType;

    /** 聚合 ID（会话 ID） */
    private Long aggregateId;

    /** 事件归属用户（消费者据此建立 AI 调用的用户上下文） */
    private Long userId;

    /** 事件载荷（JSON，只含 ID 与标量） */
    private String payload;

    private String status;

    private Integer retryCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime sentAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public void setAggregateType(String aggregateType) {
        this.aggregateType = aggregateType;
    }

    public Long getAggregateId() {
        return aggregateId;
    }

    public void setAggregateId(Long aggregateId) {
        this.aggregateId = aggregateId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public LocalDateTime getSentAt() {
        return sentAt;
    }

    public void setSentAt(LocalDateTime sentAt) {
        this.sentAt = sentAt;
    }
}
