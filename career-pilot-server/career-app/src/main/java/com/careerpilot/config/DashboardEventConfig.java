package com.careerpilot.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Dashboard 缓存失效事件的队列（阶段 10）。
 *
 * <p>绑定同一个主题交换机上的 {@code interview.completed} 与 {@code optimization.completed}；
 * 消费者只做"按 userId 清缓存"，不落库、不阻塞业务。
 *
 * <p>关闭方式：{@code app.dashboard.event-invalidation-enabled=false}（单体单独运行、
 * 无 RabbitMQ 的环境应关闭；缓存仍有 30 秒 TTL 兜底）。
 */
@Configuration
@ConditionalOnProperty(prefix = "app.dashboard", name = "event-invalidation-enabled",
        havingValue = "true", matchIfMissing = true)
public class DashboardEventConfig {

    @Value("${app.dashboard.mq.exchange:cp.dev.topic}")
    private String exchange;

    @Value("${app.dashboard.mq.queue:cp.dev.dashboard.invalidate.q}")
    private String queue;

    @Bean
    TopicExchange dashboardTopicExchange() {
        return new TopicExchange(exchange, true, false);
    }

    @Bean
    Queue dashboardInvalidateQueue() {
        return QueueBuilder.durable(queue).build();
    }

    @Bean
    Binding dashboardInterviewCompletedBinding(Queue dashboardInvalidateQueue,
                                               TopicExchange dashboardTopicExchange) {
        return BindingBuilder.bind(dashboardInvalidateQueue).to(dashboardTopicExchange)
                .with("interview.completed");
    }

    @Bean
    Binding dashboardOptimizationCompletedBinding(Queue dashboardInvalidateQueue,
                                                  TopicExchange dashboardTopicExchange) {
        return BindingBuilder.bind(dashboardInvalidateQueue).to(dashboardTopicExchange)
                .with("optimization.completed");
    }
}
