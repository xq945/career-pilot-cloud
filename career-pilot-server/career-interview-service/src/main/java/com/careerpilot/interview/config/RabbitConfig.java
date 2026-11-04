package com.careerpilot.interview.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 面试事件拓扑（阶段 8，RabbitMQ 首个业务场景）。
 *
 * <pre>
 * cp.dev.topic (topic)
 *   └─ interview.completed → cp.dev.interview.completed.q
 *                               ├─ 消费失败重试 3 次（listener 级）
 *                               └─ 死信 → cp.dev.dlx → cp.dev.interview.completed.dlq
 * </pre>
 *
 * <p>仅在 {@code app.interview.summary.async-enabled=true} 时装配：关闭异步即完全
 * 不依赖 RabbitMQ（回滚路径不需要 broker 也能启动）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.interview.summary", name = "async-enabled",
        havingValue = "true")
public class RabbitConfig {

    @Value("${app.interview.mq.exchange:cp.dev.topic}")
    private String exchange;

    @Value("${app.interview.mq.queue:cp.dev.interview.completed.q}")
    private String queue;

    @Value("${app.interview.mq.routing-key:interview.completed}")
    private String routingKey;

    @Value("${app.interview.mq.dlx:cp.dev.dlx}")
    private String dlx;

    @Value("${app.interview.mq.dlq:cp.dev.interview.completed.dlq}")
    private String dlq;

    @Bean
    TopicExchange interviewExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    DirectExchange interviewDlx() {
        return new DirectExchange(dlx, true, false);
    }

    @Bean
    Queue interviewCompletedQueue() {
        return QueueBuilder.durable(queue)
                .deadLetterExchange(dlx)
                .deadLetterRoutingKey(dlq)
                .build();
    }

    @Bean
    Binding interviewCompletedBinding(Queue interviewCompletedQueue, TopicExchange interviewExchange) {
        return BindingBuilder.bind(interviewCompletedQueue).to(interviewExchange).with(routingKey);
    }

    @Bean
    Queue interviewCompletedDlq() {
        return QueueBuilder.durable(dlq).build();
    }

    @Bean
    Binding interviewCompletedDlqBinding(Queue interviewCompletedDlq, DirectExchange interviewDlx) {
        return BindingBuilder.bind(interviewCompletedDlq).to(interviewDlx).with(dlq);
    }
}
