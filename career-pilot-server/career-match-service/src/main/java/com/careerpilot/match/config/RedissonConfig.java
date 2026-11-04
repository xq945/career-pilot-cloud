package com.careerpilot.match.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.careerpilot.config.OptimizationAsyncProperties;

/**
 * Redisson 客户端配置（M16；阶段 7 随优化链路迁入 career-match）。
 *
 * <p>{@code app.optimization.async.enabled=false} 时不创建客户端（创建优化走同步执行）；
 * 开启时 Redis 是启动前置依赖（主动探活，fail-fast）。
 */
@Configuration
public class RedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(RedissonConfig.class);

    @Bean(destroyMethod = "shutdown")
    @ConditionalOnProperty(prefix = "app.optimization.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public RedissonClient redissonClient(OptimizationAsyncProperties properties) {
        Config config = new Config();
        config.useSingleServer()
                .setAddress(properties.redisAddress())
                .setDatabase(properties.redisDatabase())
                .setConnectionMinimumIdleSize(2)
                .setConnectionPoolSize(16);
        RedissonClient client = Redisson.create(config);
        // 主动探活：把"Redis 不可用"暴露在启动阶段
        client.getBucket("career-pilot:startup-probe").isExists();
        log.info("Redisson 已连接：address={} database={}", properties.redisAddress(), properties.redisDatabase());
        return client;
    }

}
