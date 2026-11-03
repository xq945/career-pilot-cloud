package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.ai.LlmApiKeyCipher;
import com.careerpilot.ai.LlmBaseUrlValidator;
import com.careerpilot.ai.LlmProviderRegistry;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.LlmProviderSaveRequest;
import com.careerpilot.dto.LlmProviderTestResult;
import com.careerpilot.dto.LlmProviderUpdateRequest;
import com.careerpilot.dto.LlmProviderView;
import com.careerpilot.entity.LlmProvider;
import com.careerpilot.mapper.LlmProviderMapper;

/**
 * 模型服务管理（《API接口设计》§3A.1–§3A.6，M14）。
 *
 * <p>四条硬约束（实现与文案均不得违背）：
 * <ol>
 *   <li>**Key 只存密文**——加密与掩码都走 {@link LlmApiKeyCipher}，任何响应不携带明文或密文；</li>
 *   <li>**内置不可删**——内置三家只允许改 model / apiKey / temperature，baseUrl 与存在性不可变；</li>
 *   <li>**默认必须可用**——设为默认前校验已启用且已配置 Key；删除/停用默认服务前必须先切换；</li>
 *   <li>**快照不随配置漂移**——配置变更后仅对**新发起**的调用生效（注册中心缓存定向失效），
 *       历史记录保留创建时的快照（快照列由会话/优化服务在创建时写入）。</li>
 * </ol>
 *
 * <p>归属校验同全项目惯例：查询必带 user_id，查不到一律 404（不区分"不存在"与"他人"）。
 */
@Service
public class LlmProviderService {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderService.class);

    /** 自定义服务标识前缀（custom-1、custom-2…） */
    private static final String CUSTOM_KEY_PREFIX = "custom-";

    /** 内置服务的初始默认项 */
    private static final String DEFAULT_BUILTIN_KEY = "deepseek";

    /** 内置服务定义（产品预置，不随环境变化；《API接口设计》§3A.1） */
    private record BuiltinDefinition(String providerKey, String displayName, String kind,
                                     String baseUrl, String model) {
    }

    private static final List<BuiltinDefinition> BUILTINS = List.of(
            new BuiltinDefinition("deepseek", "DeepSeek", LlmProvider.KIND_DEEPSEEK,
                    "https://api.deepseek.com", "deepseek-flash"),
            new BuiltinDefinition("kimi", "Kimi", LlmProvider.KIND_OPENAI,
                    "https://api.moonshot.cn/v1", "kimi-latest"),
            new BuiltinDefinition("glm", "智谱 GLM", LlmProvider.KIND_OPENAI,
                    "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"));

    private final LlmProviderMapper llmProviderMapper;

    private final LlmApiKeyCipher apiKeyCipher;

    private final LlmProviderRegistry llmProviderRegistry;

    public LlmProviderService(LlmProviderMapper llmProviderMapper, LlmApiKeyCipher apiKeyCipher,
                              LlmProviderRegistry llmProviderRegistry) {
        this.llmProviderMapper = llmProviderMapper;
        this.apiKeyCipher = apiKeyCipher;
        this.llmProviderRegistry = llmProviderRegistry;
    }

    // ---------------------------------------------------------------- 列表（§3A.1）

    /**
     * 服务列表：内置 + 自定义，按 {@code builtin desc, id asc} 排序。
     * **懒初始化**：该用户无任何记录时先写入内置三家（deepseek 为默认）——
     * 不打开设置页的用户不产生任何记录，AI 调用仍走平台默认（行为与 M13 一致）。
     */
    public List<LlmProviderView> list(Long userId) {
        ensureBuiltinsInitialized(userId);
        List<LlmProvider> providers = llmProviderMapper.selectList(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getUserId, userId)
                .orderByDesc(LlmProvider::getBuiltin)
                .orderByAsc(LlmProvider::getId));
        return providers.stream().map(this::toView).toList();
    }

    private void ensureBuiltinsInitialized(Long userId) {
        Long count = llmProviderMapper.selectCount(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getUserId, userId));
        if (count != null && count > 0) {
            return;
        }
        log.info("初始化内置模型服务：userId={} 数量={}", userId, BUILTINS.size());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (BuiltinDefinition definition : BUILTINS) {
            LlmProvider provider = new LlmProvider();
            provider.setUserId(userId);
            provider.setProviderKey(definition.providerKey());
            provider.setDisplayName(definition.displayName());
            provider.setKind(definition.kind());
            provider.setBaseUrl(definition.baseUrl());
            provider.setModel(definition.model());
            provider.setTemperature(LlmProvider.DEFAULT_TEMPERATURE);
            provider.setIsDefault(DEFAULT_BUILTIN_KEY.equals(definition.providerKey())
                    ? LlmProvider.FLAG_TRUE : LlmProvider.FLAG_FALSE);
            provider.setBuiltin(LlmProvider.FLAG_TRUE);
            provider.setEnabled(LlmProvider.FLAG_TRUE);
            provider.setCreatedAt(now);
            provider.setUpdatedAt(now);
            try {
                llmProviderMapper.insert(provider);
            }
            catch (DuplicateKeyException ex) {
                // 并发首次访问：唯一键 (user_id, provider_key) 兜底，另一方已完成初始化
                log.debug("内置模型服务已由并发请求初始化：userId={} providerKey={}",
                        userId, definition.providerKey());
            }
        }
    }

    // ---------------------------------------------------------------- 新增（§3A.2）

    /** 新增自定义服务：kind 固定 OPENAI，providerKey 服务端生成 */
    public LlmProviderView create(Long userId, LlmProviderSaveRequest request) {
        ensureBuiltinsInitialized(userId);
        LlmProvider provider = new LlmProvider();
        provider.setUserId(userId);
        provider.setProviderKey(nextCustomKey(userId));
        provider.setDisplayName(request.getDisplayName().trim());
        provider.setKind(LlmProvider.KIND_OPENAI);
        // 用户可填写任意 baseUrl，服务端会主动请求：入库前做 SSRF 校验（阶段 1 · B4）
        String baseUrl = request.getBaseUrl().trim();
        LlmBaseUrlValidator.validate(baseUrl);
        provider.setBaseUrl(baseUrl);
        provider.setModel(request.getModel().trim());
        provider.setTemperature(request.getTemperature() == null
                ? LlmProvider.DEFAULT_TEMPERATURE : request.getTemperature());
        LlmApiKeyCipher.Encrypted encrypted = apiKeyCipher.encrypt(request.getApiKey().trim());
        provider.setApiKeyCiphertext(encrypted.ciphertext());
        provider.setApiKeyNonce(encrypted.nonce());
        provider.setIsDefault(LlmProvider.FLAG_FALSE);
        provider.setBuiltin(LlmProvider.FLAG_FALSE);
        provider.setEnabled(LlmProvider.FLAG_TRUE);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        provider.setCreatedAt(now);
        provider.setUpdatedAt(now);
        llmProviderMapper.insert(provider);
        log.info("自定义模型服务已创建：providerKey={}", provider.getProviderKey());
        return toView(provider);
    }

    /** 生成下一个自定义标识：现有 custom-N 的最大 N + 1（删除后编号不回收） */
    private String nextCustomKey(Long userId) {
        List<LlmProvider> existing = llmProviderMapper.selectList(Wrappers.<LlmProvider>lambdaQuery()
                .select(LlmProvider::getProviderKey)
                .eq(LlmProvider::getUserId, userId)
                .likeRight(LlmProvider::getProviderKey, CUSTOM_KEY_PREFIX));
        int max = 0;
        for (LlmProvider provider : existing) {
            try {
                max = Math.max(max, Integer.parseInt(
                        provider.getProviderKey().substring(CUSTOM_KEY_PREFIX.length())));
            }
            catch (NumberFormatException ignored) {
                // 非标准编号（不应出现）：跳过
            }
        }
        return CUSTOM_KEY_PREFIX + (max + 1);
    }

    // ---------------------------------------------------------------- 更新（§3A.3）

    /** 更新服务：字段缺省或空串 = 不修改；内置服务的 baseUrl 忽略；改配置后缓存定向失效 */
    public LlmProviderView update(Long userId, Long id, LlmProviderUpdateRequest request) {
        LlmProvider provider = getOwned(userId, id);
        boolean changed = false;

        if (notBlank(request.getDisplayName())) {
            provider.setDisplayName(request.getDisplayName().trim());
            changed = true;
        }
        if (notBlank(request.getModel())) {
            provider.setModel(request.getModel().trim());
            changed = true;
        }
        if (notBlank(request.getBaseUrl())) {
            if (provider.builtinFlag()) {
                log.debug("内置服务的 baseUrl 更新被忽略：providerKey={}", provider.getProviderKey());
            }
            else {
                String baseUrl = request.getBaseUrl().trim();
                LlmBaseUrlValidator.validate(baseUrl);
                provider.setBaseUrl(baseUrl);
                changed = true;
            }
        }
        if (request.getTemperature() != null) {
            provider.setTemperature(request.getTemperature());
            changed = true;
        }
        if (notBlank(request.getApiKey())) {
            LlmApiKeyCipher.Encrypted encrypted = apiKeyCipher.encrypt(request.getApiKey().trim());
            provider.setApiKeyCiphertext(encrypted.ciphertext());
            provider.setApiKeyNonce(encrypted.nonce());
            changed = true;
        }

        if (changed) {
            provider.setUpdatedAt(LocalDateTime.now().withNano(0));
            llmProviderMapper.updateById(provider);
            llmProviderRegistry.evict(userId);
            log.info("模型服务已更新：providerKey={}", provider.getProviderKey());
        }
        return toView(provider);
    }

    // ---------------------------------------------------------------- 删除（§3A.4）

    /** 删除自定义服务：内置不可删；当前默认不可删（先切换） */
    public void delete(Long userId, Long id) {
        LlmProvider provider = getOwned(userId, id);
        if (provider.builtinFlag()) {
            throw BusinessException.badRequest("内置服务不可删除");
        }
        if (provider.defaultFlag()) {
            throw BusinessException.badRequest("该服务是当前默认服务，请先切换默认后再删除");
        }
        llmProviderMapper.deleteById(id);
        llmProviderRegistry.evict(userId);
        log.info("模型服务已删除：providerKey={}", provider.getProviderKey());
    }

    // ---------------------------------------------------------------- 连通性测试（§3A.5）

    /**
     * 连通性测试：1 次最小真实调用。**失败是预期结果**——一律返回 200 + ok:false
     * 与可读原因（不抛 502），由前端就地展示。
     */
    public LlmProviderTestResult test(Long userId, Long id) {
        LlmProvider provider = getOwned(userId, id);
        try {
            // 历史数据可能早于校验规则入库，测试连通性前再校验一次（SSRF 防护）
            LlmBaseUrlValidator.validate(provider.getBaseUrl());
        }
        catch (BusinessException ex) {
            return LlmProviderTestResult.failure(ex.getMessage());
        }
        if (!provider.enabledFlag()) {
            return LlmProviderTestResult.failure("该服务已停用，请先启用");
        }
        if (!provider.hasApiKey()) {
            return LlmProviderTestResult.failure("尚未配置 API Key");
        }
        long startNanos = System.nanoTime();
        try {
            ChatClient chatClient = llmProviderRegistry.clientForProvider(provider);
            ChatResponse response = chatClient.prompt()
                    .user("你好，请只回复两个字：正常")
                    .call()
                    .chatResponse();
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            String model = response == null || response.getMetadata() == null
                    ? null : response.getMetadata().getModel();
            log.info("模型服务连通性测试通过：providerKey={} model={} 耗时={}ms",
                    provider.getProviderKey(), model, latencyMs);
            return LlmProviderTestResult.success(model, latencyMs);
        }
        catch (Exception ex) {
            // 只记类型不记消息（消息可能含请求细节，《系统架构设计》§7.4）
            log.warn("模型服务连通性测试失败：providerKey={} 异常={}",
                    provider.getProviderKey(), ex.getClass().getSimpleName());
            return LlmProviderTestResult.failure(describeFailure(ex));
        }
    }

    /** 把底层异常翻译为一句话的用户可读原因（不泄露 Key 与请求细节） */
    private static String describeFailure(Exception ex) {
        String message = (ex.getMessage() == null ? "" : ex.getMessage()).toLowerCase();
        if (message.contains("401") || message.contains("unauthorized")
                || message.contains("invalid api key") || message.contains("authentication")) {
            return "API Key 无效或不被服务端接受";
        }
        if (message.contains("403") || message.contains("forbidden")) {
            return "服务端拒绝访问（403），请检查账号权限";
        }
        if (message.contains("404") || message.contains("model not found")
                || message.contains("does not exist")) {
            return "接口地址或模型名不正确（404）";
        }
        if (message.contains("timeout") || message.contains("timed out")) {
            return "连接超时，请检查接口地址与网络";
        }
        if (message.contains("connect") || message.contains("unknownhost")
                || message.contains("unresolved")) {
            return "无法连接该地址，请检查接口地址";
        }
        return "调用失败（" + ex.getClass().getSimpleName() + "）";
    }

    // ---------------------------------------------------------------- 切换默认（§3A.6）

    /** 切换默认服务：目标必须启用且已配置 Key；同事务内保证"每用户至多一个默认" */
    @Transactional
    public LlmProviderView setDefault(Long userId, Long id) {
        LlmProvider provider = getOwned(userId, id);
        if (!provider.enabledFlag()) {
            throw BusinessException.badRequest("该服务已停用，无法设为默认");
        }
        if (!provider.hasApiKey()) {
            throw BusinessException.badRequest("该服务尚未配置 API Key，无法设为默认");
        }

        LocalDateTime now = LocalDateTime.now().withNano(0);
        LlmProvider clear = new LlmProvider();
        clear.setIsDefault(LlmProvider.FLAG_FALSE);
        clear.setUpdatedAt(now);
        llmProviderMapper.update(clear, Wrappers.<LlmProvider>lambdaUpdate()
                .eq(LlmProvider::getUserId, userId)
                .eq(LlmProvider::getIsDefault, LlmProvider.FLAG_TRUE));

        provider.setIsDefault(LlmProvider.FLAG_TRUE);
        provider.setUpdatedAt(now);
        llmProviderMapper.updateById(provider);

        // 缓存定向失效：后续 AI 调用立即使用新默认，无需重启（§3A.6）
        llmProviderRegistry.evict(userId);
        log.info("默认模型服务已切换：providerKey={}", provider.getProviderKey());
        return toView(provider);
    }

    // ---------------------------------------------------------------- 内部

    private LlmProvider getOwned(Long userId, Long id) {
        LlmProvider provider = llmProviderMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getId, id)
                .eq(LlmProvider::getUserId, userId));
        if (provider == null) {
            throw BusinessException.notFound("模型服务不存在");
        }
        return provider;
    }

    private LlmProviderView toView(LlmProvider provider) {
        LlmProviderView view = new LlmProviderView();
        view.setId(provider.getId());
        view.setProviderKey(provider.getProviderKey());
        view.setDisplayName(provider.getDisplayName());
        view.setKind(provider.getKind());
        view.setBaseUrl(provider.getBaseUrl());
        view.setModel(provider.getModel());
        view.setTemperature(provider.getTemperature());
        view.setMaskedApiKey(maskOf(provider));
        view.setIsDefault(provider.defaultFlag());
        view.setBuiltin(provider.builtinFlag());
        view.setEnabled(provider.enabledFlag());
        return view;
    }

    /**
     * 掩码：解密后取前 3 后 3，明文只在方法内存在、即刻丢弃。
     * 解密失败（主密钥变更等）时列表仍可用——掩码降级为全打码并记 WARN。
     */
    private String maskOf(LlmProvider provider) {
        if (!provider.hasApiKey()) {
            return null;
        }
        try {
            return LlmApiKeyCipher.mask(apiKeyCipher.decrypt(
                    provider.getApiKeyCiphertext(), provider.getApiKeyNonce()));
        }
        catch (RuntimeException ex) {
            log.warn("模型服务掩码解密失败（主密钥可能已变更）：providerKey={}", provider.getProviderKey());
            return "***";
        }
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

}
