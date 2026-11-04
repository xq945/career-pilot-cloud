package com.careerpilot.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * MQ 消费幂等表（阶段 8，cp_interview.mq_consumed_message）。
 *
 * <p>主键即消息 {@code messageId}：重复投递时插入冲突，消费者直接跳过。
 * 记录在总评**成功写入后**与总评同事务提交，避免"标记已消费但总评未写入"。
 */
@TableName("mq_consumed_message")
public class MqConsumedMessage {

    @TableId(type = IdType.INPUT)
    private String messageId;

    /** 消费者名（同一消息可能被多个服务消费，幂等按 服务+消息 维度） */
    private String consumer;

    private LocalDateTime consumedAt;

    public MqConsumedMessage() {
    }

    public MqConsumedMessage(String messageId, String consumer, LocalDateTime consumedAt) {
        this.messageId = messageId;
        this.consumer = consumer;
        this.consumedAt = consumedAt;
    }

    public String getMessageId() {
        return messageId;
    }

    public void setMessageId(String messageId) {
        this.messageId = messageId;
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = consumer;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }
}
