package com.careerpilot.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson 客户端配置（M16）。
 *
 * <p>显式自建 {@link RedissonClient}（不引 redisson-spring-boot-starter）：绕开
 * spring-data-redis 集成层，连接参数全部来自 {@link OptimizationAsyncProperties}，
 * 与项目"少魔法、配置显式"的风格一致（《系统架构设计》§2）。
 *
 * <p>{@code app.optimization.async.enabled=false} 时**不创建客户端**（也不要求 Redis 可用）
 * ——此时创建优化走同步执行（no Redis 环境的回退开关）。
 *
 * <p>仅当异步开关打开时，Redis 才是**启动前置依赖**（README 的启动步骤已注明）：
 * 连接失败即启动失败（fail-fast，避免"应用起来了但任务永远不执行"的隐性故障）。
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
        // 主动探活：把"Redis 不可用"暴露在启动阶段（与 DataSource 懒初始化的坑相反，此处刻意 fail-fast）
        client.getBucket("career-pilot:startup-probe").isExists();
        log.info("Redisson 已连接：address={} database={}", properties.redisAddress(), properties.redisDatabase());
        return client;
    }

}
