package com.careerpilot.ai;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.careerpilot.config.LlmProperties;
import com.careerpilot.entity.LlmProvider;

import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientImpl;
import com.openai.core.ClientOptions;
import com.openai.core.Timeout;
import com.openai.credential.BearerTokenCredential;

/**
 * 模型服务注册中心（M14）：按服务配置构建并缓存 {@link ChatClient}。
 *
 * <p>两类客户端：
 * <ul>
 *   <li>**平台默认**：复用 Spring AI 自动配置的 DeepSeek 客户端（构造时 build 一次），
 *       行为与 M13 逐字节一致；</li>
 *   <li>**用户服务**：按 {@code kind} 手工构建——{@code DEEPSEEK} 走原生
 *       {@link DeepSeekChatModel}（保留 {@code disableThinking()} 的现状行为），
 *       {@code OPENAI} 走 OpenAI 兼容协议（Kimi/GLM/自定义）。</li>
 * </ul>
 *
 * <p>缓存键为 {@code userId:providerKey}（每用户一份配置，配置变更后由
 * {@link #evict(Long)} 定向失效、下次访问按新配置惰性重建——无需重启，
 * 《API接口设计》§3A.6）。
 *
 * <p>构建细节依据《系统架构设计》§11 的 V8 结论：OpenAI 兼容客户端经
 * {@link SpringAiOpenAiHttpClient}（spring-ai-openai 核心库内）构建，
 * baseUrl 未以版本段结尾时自动补 {@code /v1}。
 */
@Component
public class LlmProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderRegistry.class);

    /** baseUrl 末尾的版本段（/v1、/v2beta 等），存在则不再补 /v1 */
    private static final Pattern TRAILING_VERSION = Pattern.compile("/v\\d+[a-zA-Z0-9]*$");

    /** 解析结果：可用客户端 + 钉住用的服务标识（null = 平台默认） */
    public record ResolvedClient(ChatClient chatClient, String providerKey) {
    }

    private final LlmProviderResolver resolver;

    private final LlmApiKeyCipher apiKeyCipher;

    private final LlmProperties properties;

    private final RestClient.Builder restClientBuilder;

    /**
     * 用户级 DeepSeek 客户端的重试模板：1 次重试（共 2 次尝试）、固定 500ms 退避。
     * 必须显式设置——{@code DeepSeekChatModel.Builder} 的默认模板是
     * 10 次重试 / 2s×5 / 最大 180s，会把一次 60s 读超时放大到分钟级（审计 B2）。
     */
    private final RetryTemplate retryTemplate;

    private final ChatClient platformDefaultClient;

    private final Map<String, ChatClient> clientCache = new ConcurrentHashMap<>();

    public LlmProviderRegistry(ChatClient.Builder platformChatClientBuilder,
                               LlmProviderResolver resolver,
                               LlmApiKeyCipher apiKeyCipher,
                               LlmProperties properties,
                               RestClient.Builder restClientBuilder) {
        this.resolver = resolver;
        this.apiKeyCipher = apiKeyCipher;
        this.properties = properties;
        this.restClientBuilder = restClientBuilder;
        this.retryTemplate = new RetryTemplate(RetryPolicy.builder()
                .maxRetries(1)
                .delay(Duration.ofMillis(500))
                .multiplier(1.0)
                .maxDelay(Duration.ofSeconds(1))
                .build());
        // 平台默认：直接复用自动配置的客户端（未配置任何服务的用户行为与 M13 一致）
        this.platformDefaultClient = platformChatClientBuilder.build();
    }

    /** 当前应使用的客户端：用户默认服务 → 平台默认 */
    public ResolvedClient resolveForUser(Long userId) {
        Optional<LlmProvider> provider = resolver.resolveDefault(userId);
        if (provider.isEmpty()) {
            return new ResolvedClient(platformDefaultClient, null);
        }
        return new ResolvedClient(clientForProvider(provider.get()), provider.get().getProviderKey());
    }

    /** 按钉住的服务标识解析（快照列重放；不可用时回退平台默认） */
    public ResolvedClient resolveByKey(Long userId, String providerKey) {
        Optional<LlmProvider> provider = resolver.resolveByKey(userId, providerKey);
        if (provider.isEmpty()) {
            return new ResolvedClient(platformDefaultClient, null);
        }
        return new ResolvedClient(clientForProvider(provider.get()), provider.get().getProviderKey());
    }

    /** 平台默认客户端（连通性测试等需要显式指定客户端的场景） */
    public ChatClient platformDefaultClient() {
        return platformDefaultClient;
    }

    /** 为该服务构建（或取缓存）一个独立客户端；失败（Key 解密失败等）抛业务异常 */
    public ChatClient clientForProvider(LlmProvider provider) {
        String cacheKey = provider.getUserId() + ":" + provider.getProviderKey();
        return clientCache.computeIfAbsent(cacheKey, key -> buildClient(provider));
    }

    /** 配置变更后清空该用户的客户端缓存（下次访问按新配置重建，无需重启） */
    public void evict(Long userId) {
        String prefix = userId + ":";
        clientCache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    // ---------------------------------------------------------------- 构建

    private ChatClient buildClient(LlmProvider provider) {
        String apiKey = apiKeyCipher.decrypt(provider.getApiKeyCiphertext(), provider.getApiKeyNonce());
        log.info("构建模型服务客户端：providerKey={} kind={} model={}",
                provider.getProviderKey(), provider.getKind(), provider.getModel());
        if (LlmProvider.KIND_DEEPSEEK.equals(provider.getKind())) {
            return buildDeepSeekClient(provider, apiKey);
        }
        return buildOpenAiCompatibleClient(provider, apiKey);
    }

    private ChatClient buildDeepSeekClient(LlmProvider provider, String apiKey) {
        // 注入 Spring Boot 自动配置的 RestClient.Builder：继承 spring.http.clients
        // 的连接/读取超时（与平台默认 DeepSeek 客户端一致），不再使用 SDK 默认无限等待。
        DeepSeekApi api = DeepSeekApi.builder()
                .baseUrl(provider.getBaseUrl())
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder.clone())
                .build();
        DeepSeekChatOptions options = DeepSeekChatOptions.builder()
                .model(provider.getModel())
                .temperature(temperatureOf(provider))
                .disableThinking()
                .build();
        DeepSeekChatModel chatModel = DeepSeekChatModel.builder()
                .deepSeekApi(api)
                .options(options)
                .retryTemplate(retryTemplate)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    private ChatClient buildOpenAiCompatibleClient(LlmProvider provider, String apiKey) {
        OpenAIClient openAiClient = buildOpenAiClient(provider.getBaseUrl(), apiKey);
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(provider.getModel())
                .temperature(temperatureOf(provider))
                .build();
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .openAiClient(openAiClient)
                .openAiClientAsync(openAiClient.async())
                .options(options)
                .build();
        return ChatClient.builder(chatModel).build();
    }

    private OpenAIClient buildOpenAiClient(String baseUrl, String apiKey) {
        Timeout timeout = Timeout.builder()
                .connect(properties.connectTimeout())
                .read(properties.readTimeout())
                .build();
        ClientOptions options = ClientOptions.Companion.builder()
                .apiKey(apiKey)
                .credential(BearerTokenCredential.create(apiKey))
                .baseUrl(resolveVersionedBaseUrl(baseUrl))
                .timeout(timeout)
                .httpClient(SpringAiOpenAiHttpClient.builder().timeout(timeout).build())
                .build();
        return new OpenAIClientImpl(options);
    }

    /** baseUrl 未以版本段结尾时补 {@code /v1}（OpenAI SDK 在其后拼 /chat/completions） */
    static String resolveVersionedBaseUrl(String baseUrl) {
        String stripped = baseUrl == null ? "" : baseUrl.trim();
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return TRAILING_VERSION.matcher(stripped).find() ? stripped : stripped + "/v1";
    }

    private static Double temperatureOf(LlmProvider provider) {
        return provider.getTemperature() == null
                ? LlmProvider.DEFAULT_TEMPERATURE.doubleValue()
                : provider.getTemperature().doubleValue();
    }

}
