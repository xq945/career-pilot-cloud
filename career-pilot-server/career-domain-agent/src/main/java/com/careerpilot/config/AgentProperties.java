package com.careerpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 求职智能体配置（《开发顺序》M8、《API接口设计》§8.1）。
 *
 * <p>配置错误必须在**启动时**暴露（延续 MatchScoringProperties / FileStorageProperties
 * 的 fail-fast 惯例），而不是等到首次对话才失败。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**：
 * 组件扫描注册的配置类走构造器自动装配，会把构造参数当成待注入的 bean 而启动失败。
 */
@ConfigurationProperties(prefix = "app.agent")
public class AgentProperties {

    /** 单次对话总时长预算（毫秒），对应《API接口设计》§8.1「总时长不超过 120 秒」 */
    private final long totalTimeoutMillis;

    /** 单次请求工具调用次数上限（《Agent设计》§8：≤5 次）；软上限由 AgentToolCallRecorder 执行 */
    private final int maxToolCalls;

    /** 历史回放的消息条数上限（只回放 user/assistant 文本） */
    private final int historyMessageLimit;

    /** 历史回放的累计字符上限（从最新往回累计，超出即丢弃更早的消息） */
    private final int historyCharLimit;

    /** 注入提示词的资源清单条数上限（简历/岗位各一份清单） */
    private final int catalogLimit;

    /**
     * 构造器绑定（Spring Boot 的 relaxed binding：yml 中的 kebab-case 键绑定到驼峰构造参数）。
     * 校验放在构造器：配置不合法即启动失败。
     */
    public AgentProperties(long totalTimeoutMillis, int maxToolCalls, int historyMessageLimit,
                           int historyCharLimit, int catalogLimit) {
        requirePositive("app.agent.total-timeout-millis", totalTimeoutMillis);
        requirePositive("app.agent.max-tool-calls", maxToolCalls);
        requirePositive("app.agent.history-message-limit", historyMessageLimit);
        requirePositive("app.agent.history-char-limit", historyCharLimit);
        requirePositive("app.agent.catalog-limit", catalogLimit);
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.maxToolCalls = maxToolCalls;
        this.historyMessageLimit = historyMessageLimit;
        this.historyCharLimit = historyCharLimit;
        this.catalogLimit = catalogLimit;
    }

    private static void requirePositive(String key, long value) {
        if (value <= 0) {
            throw new IllegalStateException(key + " 必须为正数（《API接口设计》§8.1、《Agent设计》§8）");
        }
    }

    public long totalTimeoutMillis() {
        return totalTimeoutMillis;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    public int historyMessageLimit() {
        return historyMessageLimit;
    }

    public int historyCharLimit() {
        return historyCharLimit;
    }

    public int catalogLimit() {
        return catalogLimit;
    }

}
