package com.careerpilot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 「创建优化」异步链路配置（M16，前缀 {@code app.optimization.async}）。
 *
 * <p>三个关键参数的语义（《系统架构设计》§6.5）：
 * <ul>
 *   <li>{@code pendingTimeout}：任务行处于 PENDING 超过该时长视为**投递丢失**，恢复调度补投；
 *       必须大于正常排队时间（消费者单线程）；</li>
 *   <li>{@code processingTimeout}：任务行处于 PROCESSING 超过该时长视为**消费者死亡**，
 *       恢复调度重置为 PENDING 后补投；</li>
 *   <li>{@code scanInterval}：恢复调度扫描间隔。</li>
 * </ul>
 *
 * <p>{@code enabled=false} 时创建接口走**同步执行**（与 M11 行为一致）——用于无 Redis 的
 * 环境与调试（《API接口设计》§6A.1 的兼容开关）；入队失败时也自动降级为同步执行。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**。
 */
@ConfigurationProperties(prefix = "app.optimization.async")
public class OptimizationAsyncProperties {

    private static final String DEFAULT_REDIS_ADDRESS = "redis://localhost:6379";

    private final boolean enabled;

    private final String redisAddress;

    private final int redisDatabase;

    private final Duration pendingTimeout;

    private final Duration processingTimeout;

    private final Duration scanInterval;

    /**
     * PROCESSING 超时被自动恢复的最大次数：超过后置 FAILED，避免毒任务无限重跑（阶段 1 · B3）。
     */
    private final int maxRecoveryCount;

    public OptimizationAsyncProperties(boolean enabled, String redisAddress, int redisDatabase,
                                       Duration pendingTimeout, Duration processingTimeout,
                                       Duration scanInterval, int maxRecoveryCount) {
        this.enabled = enabled;
        this.redisAddress = (redisAddress == null || redisAddress.isBlank())
                ? DEFAULT_REDIS_ADDRESS : redisAddress.trim();
        this.redisDatabase = Math.max(0, redisDatabase);
        // 30 秒小于单任务 25–80 秒：队列稍有积压就会把正常排队误判为"投递丢失"（阶段 1 · B3）
        this.pendingTimeout = pendingTimeout == null ? Duration.ofMinutes(5) : pendingTimeout;
        this.processingTimeout = processingTimeout == null ? Duration.ofMinutes(10) : processingTimeout;
        this.scanInterval = scanInterval == null ? Duration.ofSeconds(60) : scanInterval;
        this.maxRecoveryCount = Math.max(1, maxRecoveryCount);
        if (this.processingTimeout.compareTo(this.pendingTimeout) <= 0) {
            throw new IllegalStateException(
                    "app.optimization.async.processing-timeout 必须大于 pending-timeout（否则刚领取的任务会被误判为死亡）");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String redisAddress() {
        return redisAddress;
    }

    public int redisDatabase() {
        return redisDatabase;
    }

    public Duration pendingTimeout() {
        return pendingTimeout;
    }

    public Duration processingTimeout() {
        return processingTimeout;
    }

    public Duration scanInterval() {
        return scanInterval;
    }

    public int maxRecoveryCount() {
        return maxRecoveryCount;
    }

}
