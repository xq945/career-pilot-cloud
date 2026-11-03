package com.careerpilot.poc.consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class PocListener {

    private static final Logger log = LoggerFactory.getLogger(PocListener.class);

    @RabbitListener(queues = "poc.test.q")
    public void onMessage(String message) {
        log.info("poc.mq.received: {}", message);
        if (message.startsWith("fail:")) {
            throw new IllegalStateException("poc intentional failure: " + message);
        }
    }

    @RabbitListener(queues = "poc.test.dlq")
    public void onDeadLetter(String message) {
        log.warn("poc.dlq.received: {}", message);
    }
}
