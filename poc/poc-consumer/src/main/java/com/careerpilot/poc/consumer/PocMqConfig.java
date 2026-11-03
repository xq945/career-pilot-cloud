package com.careerpilot.poc.consumer;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PocMqConfig {

    @Bean
    public DirectExchange pocExchange() {
        return new DirectExchange("poc.exchange", true, false);
    }

    @Bean
    public Queue pocQueue() {
        return QueueBuilder.durable("poc.test.q")
                .deadLetterExchange("poc.dlx")
                .deadLetterRoutingKey("poc.test.dlq")
                .build();
    }

    @Bean
    public Binding pocBinding() {
        return BindingBuilder.bind(pocQueue()).to(pocExchange()).with("poc.test");
    }

    @Bean
    public DirectExchange pocDlx() {
        return new DirectExchange("poc.dlx", true, false);
    }

    @Bean
    public Queue pocDlq() {
        return QueueBuilder.durable("poc.test.dlq").build();
    }

    @Bean
    public Binding pocDlqBinding() {
        return BindingBuilder.bind(pocDlq()).to(pocDlx()).with("poc.test.dlq");
    }
}
