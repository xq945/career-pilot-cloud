package com.careerpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 面试总评生成方式（阶段 8）。构造器绑定（record），配置缺失时取 record 默认值：
 * {@code async-enabled=false} 表示沿用同步路径（回滚开关）。
 *
 * <p>开启后：最后一题提交 → 业务事务内写 {@code outbox_event} → 立即返回；
 * {@code career-interview} 的投递器把事件发到 RabbitMQ，消费者生成总评并回写。
 *
 * @param asyncEnabled true = 异步（RabbitMQ + outbox）；false = 提交回答时同步生成（历史行为）
 */
@ConfigurationProperties(prefix = "app.interview.summary")
public record InterviewSummaryProperties(boolean asyncEnabled) {
}
