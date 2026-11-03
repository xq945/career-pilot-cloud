package com.careerpilot.poc.consumer;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PocController {

    private final ProviderClient providerClient;
    private final StringRedisTemplate redis;
    private final RabbitTemplate rabbit;

    @Value("${server.port}")
    private String port;

    public PocController(ProviderClient providerClient,
                         StringRedisTemplate redis,
                         RabbitTemplate rabbit) {
        this.providerClient = providerClient;
        this.redis = redis;
        this.rabbit = rabbit;
    }

    @GetMapping("/call")
    public Map<String, Object> call(@RequestParam(defaultValue = "feign") String name) {
        Map<String, Object> provider = providerClient.hello(name);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("from", "poc-consumer");
        body.put("port", port);
        body.put("provider", provider);
        return body;
    }

    @GetMapping("/redis/set")
    public Map<String, Object> redisSet(@RequestParam String key, @RequestParam String value) {
        redis.opsForValue().set(key, value);
        return Map.of("ok", true, "key", key, "value", value);
    }

    @GetMapping("/redis/get")
    public Map<String, Object> redisGet(@RequestParam String key) {
        return Map.of("key", key, "value", String.valueOf(redis.opsForValue().get(key)));
    }

    @GetMapping("/mq/send")
    public Map<String, Object> mqSend(@RequestParam(defaultValue = "hello-mq") String msg) {
        rabbit.convertAndSend("poc.exchange", "poc.test", msg);
        return Map.of("sent", msg);
    }

    @GetMapping("/mq/fail")
    public Map<String, Object> mqFail(@RequestParam(defaultValue = "boom") String msg) {
        String payload = "fail:" + msg;
        rabbit.convertAndSend("poc.exchange", "poc.test", payload);
        return Map.of("sent", payload);
    }
}
