package com.careerpilot.ai;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.AiCallProperties;
import com.careerpilot.security.CurrentUser;

import tools.jackson.core.JacksonException;

/**
 * AI 调用的统一入口（M4 定死的范式，M5–M8 直接复用；**全项目唯一与模型对话的地方**）。
 *
 * <p>职责：
 * <ul>
 *   <li>拼装消息 → 结构化输出调用（{@code .entity()} 族）→ 计时 → 结果与元数据；</li>
 *   <li>**M14 起**：客户端从 {@link LlmProviderRegistry} 解析（用户默认服务 → 平台默认），
 *       并支持按**钉住的服务标识**（快照列）发起调用（{@link #call(String, String, String, String, Class)}）；</li>
 *   <li>**M14 起**：结构化输出的可靠性两步兜底——转换器内先做**未转义引号的本地修复**
 *       （{@link JsonStringRepair}，不计入调用次数）；仍失败且属解析类失败时
 *       **追加修复指令重试**（总尝试次数见 {@code app.ai.structured-max-attempts}）。
 *       超时/网络类失败（50200 路径）**不重试**；</li>
 *   <li>框架异常统一翻译为业务错误码：**输出无法解析 → 50201；其余 → 50200**；</li>
 *   <li>成功日志记录模型名、耗时、token 消耗（《Agent设计》8 节成本核算）；
 *       失败日志只记异常类型与截断摘要，**绝不记录提示词、简历原文与 API Key**。</li>
 * </ul>
 *
 * <p>超时与重试的分层：**客户端超时**由 {@code spring.http.clients.*}（平台默认）或
 * {@code app.llm.connect-timeout / read-timeout}（用户服务）注入；**HTTP 级重试**由
 * {@code spring.ai.retry.*} 配置的 RetryTemplate 承担；本类只做**解析失败**的修复重试。
 */
@Component
public class AiCallTemplate {

    private static final Logger log = LoggerFactory.getLogger(AiCallTemplate.class);

    private static final String UNAVAILABLE_MESSAGE = "大模型服务暂时不可用，请稍后重试";

    private static final String UNPARSABLE_MESSAGE = "大模型输出无法解析，请稍后重试";

    /** 失败日志中异常消息的截断长度（脱敏与可读性的折中） */
    private static final int ERROR_DETAIL_LIMIT = 200;

    /**
     * 修复型重试时追加到 system prompt 末尾的严格 JSON 指令（M14）。
     * 只影响重试的那一次调用；首次调用不追加（保持与历史版本逐字节一致）。
     */
    private static final String STRICT_JSON_REPAIR_HINT = """

            上次输出无法被 JSON 解析器解析。请严格只输出一个可被解析的 JSON 对象：
            1) 不要输出 Markdown 代码块（如 ```json）；
            2) 不要输出任何解释文字、前后缀或注释；
            3) 字符串内的引号必须正确转义。
            """;

    private final LlmProviderRegistry llmProviderRegistry;

    private final int structuredMaxAttempts;

    /** remote：业务服务把模型调用交给 career-ai；local：本进程直接调用模型 */
    private final boolean remote;

    private final AiChatClient aiChatClient;

    public AiCallTemplate(LlmProviderRegistry llmProviderRegistry, AiCallProperties aiCallProperties,
                          @Value("${app.ai.mode:local}") String mode,
                          ObjectProvider<AiChatClient> aiChatClientProvider) {
        this.llmProviderRegistry = llmProviderRegistry;
        this.structuredMaxAttempts = aiCallProperties.structuredMaxAttempts();
        this.remote = "remote".equalsIgnoreCase(mode);
        this.aiChatClient = aiChatClientProvider.getIfAvailable();
        if (this.remote && this.aiChatClient == null) {
            throw new IllegalStateException("app.ai.mode=remote 但未装配 AiChatClient");
        }
    }

    /**
     * 结构化输出调用（当前用户默认模型服务）。
     *
     * @param scene        场景名（日志与清洗日志用，如 resume-analysis）
     * @param systemPrompt 系统提示词
     * @param userPrompt   用户消息（含待分析文本）
     * @param outputType   结构化输出类型（由 Spring AI 的 BeanOutputConverter 反序列化，
     *                     JSON Schema 会自动注入提示词）
     */
    public <T> AiCallResult<T> call(String scene, String systemPrompt, String userPrompt,
                                    Class<T> outputType) {
        if (remote) {
            return callRemote(scene, null, systemPrompt, userPrompt, outputType);
        }
        LlmProviderRegistry.ResolvedClient resolved =
                llmProviderRegistry.resolveForUser(CurrentUser.idOrNull());
        return callWithClient(scene, resolved, systemPrompt, userPrompt, outputType);
    }

    /**
     * 结构化输出调用（**按钉住的服务标识**，M14；快照列重放场景）。
     * 服务不可用（已删除/停用）时回退平台默认（见 {@link LlmProviderRegistry#resolveByKey}）。
     */
    public <T> AiCallResult<T> call(String scene, String providerKey, String systemPrompt,
                                    String userPrompt, Class<T> outputType) {
        if (remote) {
            return callRemote(scene, providerKey, systemPrompt, userPrompt, outputType);
        }
        LlmProviderRegistry.ResolvedClient resolved =
                llmProviderRegistry.resolveByKey(CurrentUser.idOrNull(), providerKey);
        return callWithClient(scene, resolved, systemPrompt, userPrompt, outputType);
    }

    /**
     * remote 模式：把一次模型调用交给 career-ai，本地只做结构化解析与修复重试。
     */
    private <T> AiCallResult<T> callRemote(String scene, String providerKey, String systemPrompt,
                                           String userPrompt, Class<T> outputType) {
        long startNanos = System.nanoTime();
        RuntimeException lastError = null;
        Long userId = CurrentUser.idOrNull();
        BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
        // 与本地模式对齐：Spring AI 在 .responseEntity(converter) 时会把格式指令注入提示词，
        // 但 remote 模式只把 system/user 两段文本交给 career-ai——若不在这里显式追加，
        // 模型拿不到 JSON Schema/格式约束，输出更容易出现字段漂移与事实违规（阶段 7 修复）。
        String userPromptWithFormat = userPrompt + "\n\n" + converter.getFormat();
        for (int attempt = 1; attempt <= structuredMaxAttempts; attempt++) {
            String attemptSystemPrompt = attempt == 1
                    ? systemPrompt
                    : systemPrompt + STRICT_JSON_REPAIR_HINT;
            if (attempt > 1) {
                log.warn("AI 输出无法解析，追加修复指令重试（remote）：scene={} attempt={}/{}",
                        scene, attempt, structuredMaxAttempts);
            }
            try {
                AiChatResponse response = aiChatClient.chat(new AiChatRequest(
                        scene, userId, providerKey, attemptSystemPrompt, userPromptWithFormat));
                if (response == null || response.content() == null || response.content().isBlank()) {
                    log.warn("AI 输出为空（remote）：scene={}", scene);
                    throw BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
                }
                T value = new RepairingStructuredOutputConverter<>(converter, scene, log)
                        .convert(response.content());
                if (value == null) {
                    throw BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
                }
                long elapsedMillis = elapsedMillis(startNanos);
                log.info("AI 调用完成（remote）：scene={} model={} 耗时={}ms promptTokens={} completionTokens={} totalTokens={}",
                        scene, response.modelName(), elapsedMillis,
                        response.promptTokens(), response.completionTokens(), response.totalTokens());
                return new AiCallResult<>(value, response.modelName(), response.providerKey(),
                        elapsedMillis, response.promptTokens(), response.completionTokens(),
                        response.totalTokens());
            }
            catch (BusinessException ex) {
                throw ex;
            }
            catch (RuntimeException ex) {
                if (!hasCause(ex, JacksonException.class)) {
                    throw translate(scene, ex, startNanos);
                }
                lastError = ex;
            }
        }
        throw translate(scene, lastError, startNanos);
    }

    /**
     * 原始模型调用（不做结构化解析）：career-ai 的 /internal/chat 使用。
     */
    public AiChatResponse complete(String scene, String providerKey, String systemPrompt, String userPrompt) {
        if (remote) {
            return aiChatClient.chat(new AiChatRequest(
                    scene, CurrentUser.idOrNull(), providerKey, systemPrompt, userPrompt));
        }
        LlmProviderRegistry.ResolvedClient resolved = (providerKey == null || providerKey.isBlank())
                ? llmProviderRegistry.resolveForUser(CurrentUser.idOrNull())
                : llmProviderRegistry.resolveByKey(CurrentUser.idOrNull(), providerKey);
        long startNanos = System.nanoTime();
        try {
            ChatResponse response = resolved.chatClient().prompt()
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .chatResponse();
            String content = textOf(response);
            if (content == null || content.isBlank()) {
                log.warn("AI 输出为空（complete）：scene={}", scene);
                throw BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
            }
            long elapsedMillis = elapsedMillis(startNanos);
            String modelName = resolveModelName(response);
            Usage usage = usageOf(response);
            Integer promptTokens = usage == null ? null : usage.getPromptTokens();
            Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
            Integer totalTokens = usage == null ? null : usage.getTotalTokens();
            log.info("AI 调用完成（complete）：scene={} model={} 耗时={}ms promptTokens={} completionTokens={} totalTokens={}",
                    scene, modelName, elapsedMillis, promptTokens, completionTokens, totalTokens);
            return new AiChatResponse(content, modelName, resolved.providerKey(),
                    promptTokens, completionTokens, totalTokens);
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw translate(scene, ex, startNanos);
        }
    }

    private <T> AiCallResult<T> callWithClient(String scene, LlmProviderRegistry.ResolvedClient resolved,
                                               String systemPrompt, String userPrompt, Class<T> outputType) {
        ChatClient chatClient = resolved.chatClient();
        // 【关键约束】绝不使用 .param(...) / .user(text, map)：
        // 一旦传入参数，Spring AI 会用 StringTemplate 渲染整段文本，
        // 提示词里的 JSON 结构示例与简历原文中的花括号会被当成模板变量而报错
        // （《问题记录 · AI集成》）。此处 system 与 user 均为最终文本。
        long startNanos = System.nanoTime();
        RuntimeException lastError = null;
        for (int attempt = 1; attempt <= structuredMaxAttempts; attempt++) {
            String attemptSystemPrompt = attempt == 1
                    ? systemPrompt
                    : systemPrompt + STRICT_JSON_REPAIR_HINT;
            if (attempt > 1) {
                log.warn("AI 输出无法解析，追加修复指令重试：scene={} attempt={}/{}",
                        scene, attempt, structuredMaxAttempts);
            }
            try {
                // 转换器包装：解析失败时先做未转义引号的本地修复（不计入调用次数）；
                // 修复后仍失败则抛出原始异常，由外层决定重试或 50201
                BeanOutputConverter<T> converter = new BeanOutputConverter<>(outputType);
                ResponseEntity<ChatResponse, T> response = chatClient.prompt()
                        .system(attemptSystemPrompt)
                        .user(userPrompt)
                        .call()
                        .responseEntity(new RepairingStructuredOutputConverter<>(converter, scene, log));
                return toCallResult(scene, response, resolved.providerKey(), startNanos);
            }
            catch (BusinessException ex) {
                throw ex;
            }
            catch (RuntimeException ex) {
                if (!hasCause(ex, JacksonException.class)) {
                    // 非解析类失败（超时/网络/上游 HTTP 错误）：不重试，直接翻译（50200）
                    throw translate(scene, ex, startNanos);
                }
                lastError = ex;
            }
        }
        // 全部尝试均因解析失败而结束：翻译为 50201
        throw translate(scene, lastError, startNanos);
    }

    private <T> AiCallResult<T> toCallResult(String scene, ResponseEntity<ChatResponse, T> response,
                                             String providerKey, long startNanos) {
        long elapsedMillis = elapsedMillis(startNanos);
        T value = response.entity();
        if (value == null) {
            // 输出为空（模型未返回可解析内容）：按「输出无法解析」处理，不落库
            log.warn("AI 输出为空：scene={} 耗时={}ms", scene, elapsedMillis);
            throw BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
        }

        ChatResponse chatResponse = response.response();
        String modelName = resolveModelName(chatResponse);
        Usage usage = usageOf(chatResponse);
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();

        log.info("AI 调用完成：scene={} model={} 耗时={}ms promptTokens={} completionTokens={} totalTokens={}",
                scene, modelName, elapsedMillis, promptTokens, completionTokens, totalTokens);
        return new AiCallResult<>(value, modelName, providerKey, elapsedMillis,
                promptTokens, completionTokens, totalTokens);
    }

    // ---------------------------------------------------------------- 带工具的对话调用（M8）

    /**
     * 求职智能体的对话调用（《API接口设计》§8.1；M8 起并入本类，保持"全项目唯一模型调用入口"）。
     *
     * <p>与 {@link #call} 的差异：输入是**消息列表 + 工具回调**、输出是自由文本；
     * 计时、日志、脱敏与异常翻译（50200/50201）完全同构。不做修复重试
     * （自由文本无解析环节，失败即为 50200）。
     *
     * <p>两个关键约定：
     * <ul>
     *   <li>**当前用户消息是 {@code history} 的最后一条** {@code UserMessage}——
     *       框架的工具调用次数计数以"最后一条 UserMessage"为本轮起点（已核实），
     *       不要改用 {@code .user(...)} 单独传；</li>
     *   <li>同样绝不使用 {@code .param(...)}（见 {@link #call} 的约束说明）。</li>
     * </ul>
     *
     * <p>工具越限不由本方法处理：框架侧配置了宽松兜底（RETURN_ERROR_RESPONSE），
     * 产品级的 5 次上限与截断判定在 {@link RecordingToolCallback} / {@link AgentToolCallRecorder}。
     */
    public AgentCallResult callWithTools(String scene, String systemPrompt,
                                         List<Message> history, List<ToolCallback> toolCallbacks) {
        LlmProviderRegistry.ResolvedClient resolved =
                llmProviderRegistry.resolveForUser(CurrentUser.idOrNull());
        ChatClient chatClient = resolved.chatClient();
        long startNanos = System.nanoTime();
        ChatResponse chatResponse;
        try {
            chatResponse = chatClient.prompt()
                    .system(systemPrompt)
                    .messages(history)
                    .toolCallbacks(toolCallbacks)
                    .call()
                    .chatResponse();
        }
        catch (BusinessException ex) {
            throw ex;
        }
        catch (RuntimeException ex) {
            throw translate(scene, ex, startNanos);
        }

        long elapsedMillis = elapsedMillis(startNanos);
        String message = textOf(chatResponse);
        if (message == null || message.isBlank()) {
            // 空回复无法构成可用结果：按「输出无法解析」处理，整轮不落库
            log.warn("AI 对话输出为空：scene={} 耗时={}ms", scene, elapsedMillis);
            throw BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
        }

        String modelName = resolveModelName(chatResponse);
        Usage usage = usageOf(chatResponse);
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();

        log.info("AI 对话完成：scene={} model={} 耗时={}ms promptTokens={} completionTokens={} totalTokens={}",
                scene, modelName, elapsedMillis, promptTokens, completionTokens, totalTokens);
        return new AgentCallResult(message, modelName, elapsedMillis,
                promptTokens, completionTokens, totalTokens);
    }

    private static String textOf(ChatResponse chatResponse) {
        if (chatResponse == null || chatResponse.getResult() == null
                || chatResponse.getResult().getOutput() == null) {
            return null;
        }
        return chatResponse.getResult().getOutput().getText();
    }

    // ---------------------------------------------------------------- 异常翻译

    /**
     * 统一翻译为 502 系列。（《Agent设计》6.4：反序列化失败 → 50201；
     * 超时/不可用 → 50200。）
     *
     * 判定走**异常链根因**：重试耗尽后抛出的可能是框架的包装异常
     * （Spring Framework 7 的 RetryException 等），顶层类型未必是原始异常。
     */
    private BusinessException translate(String scene, RuntimeException ex, long startNanos) {
        long elapsedMillis = elapsedMillis(startNanos);
        if (hasCause(ex, JacksonException.class)) {
            log.warn("AI 输出无法解析：scene={} 耗时={}ms 异常={} 摘要={}",
                    scene, elapsedMillis, ex.getClass().getSimpleName(), detailOf(ex));
            return BusinessException.aiInvalidOutput(UNPARSABLE_MESSAGE);
        }
        log.warn("AI 调用失败：scene={} 耗时={}ms 异常={} 摘要={}",
                scene, elapsedMillis, ex.getClass().getSimpleName(), detailOf(ex));
        return BusinessException.aiUnavailable(UNAVAILABLE_MESSAGE);
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable current = ex; current != null; current = current.getCause()) {
            if (type.isInstance(current)) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /**
     * 异常消息的短摘要：取**最深根因**（Jackson 的具体字段错误往往在 cause 中），
     * 折断换行并截断（防止把长响应体原样写进日志）。
     */
    private static String detailOf(Throwable ex) {
        Throwable root = ex;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        if (message == null || message.isBlank()) {
            return root.getClass().getSimpleName();
        }
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > ERROR_DETAIL_LIMIT ? flat.substring(0, ERROR_DETAIL_LIMIT) + "…" : flat;
    }

    // ---------------------------------------------------------------- 元数据

    private static String resolveModelName(ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        String model = metadata == null ? null : metadata.getModel();
        if (model == null || model.isBlank()) {
            log.warn("模型响应未返回 model 字段，modelName 记为 unknown");
            return "unknown";
        }
        return model;
    }

    private static Usage usageOf(ChatResponse chatResponse) {
        ChatResponseMetadata metadata = chatResponse == null ? null : chatResponse.getMetadata();
        return metadata == null ? null : metadata.getUsage();
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

}
