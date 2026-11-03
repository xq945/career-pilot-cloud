package com.careerpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 调用行为配置（M14，前缀 {@code app.ai}）。
 *
 * <p>{@code structuredMaxAttempts}：结构化输出的**总尝试次数**——
 * 1 = 不重试；2（默认）= 首次解析失败后，追加"仅返回合法 JSON"的修复指令重试一次。
 * **只对解析失败（50201 路径）生效**：超时/网络类失败（50200 路径）不触发修复重试
 * （请求已失败，重试只会重复失败并延长等待）。
 *
 * <p>解析失败时另有**本地修复**（未转义引号，见 {@code JsonStringRepair}）先于重试执行；
 * 本地修复不计入尝试次数。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**。
 */
@ConfigurationProperties(prefix = "app.ai")
public class AiCallProperties {

    private final int structuredMaxAttempts;

    public AiCallProperties(int structuredMaxAttempts) {
        if (structuredMaxAttempts < 1 || structuredMaxAttempts > 3) {
            throw new IllegalStateException(
                    "app.ai.structured-max-attempts 必须在 1–3 之间，当前为 " + structuredMaxAttempts);
        }
        this.structuredMaxAttempts = structuredMaxAttempts;
    }

    public int structuredMaxAttempts() {
        return structuredMaxAttempts;
    }

}
