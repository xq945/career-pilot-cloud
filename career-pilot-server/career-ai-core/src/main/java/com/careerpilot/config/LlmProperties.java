package com.careerpilot.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 模型服务相关配置（M14，前缀 {@code app.llm}；《系统架构设计》§7.4）。
 *
 * <p>{@code encryptionKey} 是用户级模型服务 API Key 的**加密主密钥**：
 * 优先按 Base64 解码为 16/32 字节直接作为 AES 密钥，否则以 SHA-256 派生。
 * 未配置时**不阻止启动**（本地开发便利），由 {@code com.careerpilot.ai.LlmApiKeyCipher}
 * 打印 WARN 并降级为开发用 fallback 密钥——演示与部署必须显式配置。
 *
 * <p>{@code connectTimeout / readTimeout} 用于用户级服务构建 OpenAI 兼容客户端；
 * 缺省 10s / 60s 与平台默认（{@code spring.http.clients.*}）对齐，
 * 保证"切换服务"不改变整体超时语义。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**
 * （构造器绑定会被组件扫描当成依赖注入，见 MatchScoringProperties 的说明）。
 */
@ConfigurationProperties(prefix = "app.llm")
public class LlmProperties {

    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(60);

    private final String encryptionKey;

    private final Duration connectTimeout;

    private final Duration readTimeout;

    public LlmProperties(String encryptionKey, Duration connectTimeout, Duration readTimeout) {
        this.encryptionKey = encryptionKey == null ? "" : encryptionKey.trim();
        this.connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        this.readTimeout = readTimeout == null ? DEFAULT_READ_TIMEOUT : readTimeout;
    }

    /** 加密主密钥（原始字符串；空串表示未配置） */
    public String encryptionKey() {
        return encryptionKey;
    }

    public boolean hasEncryptionKey() {
        return !encryptionKey.isBlank();
    }

    public Duration connectTimeout() {
        return connectTimeout;
    }

    public Duration readTimeout() {
        return readTimeout;
    }

}
