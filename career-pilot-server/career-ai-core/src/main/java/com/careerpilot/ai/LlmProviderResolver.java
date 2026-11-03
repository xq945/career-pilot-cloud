package com.careerpilot.ai;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.entity.LlmProvider;
import com.careerpilot.mapper.LlmProviderMapper;

/**
 * 模型服务解析（M14）：决定"当前该用哪个 provider"（《API接口设计》§3A 章节说明）。
 *
 * <p>解析顺序：**用户默认服务 → 平台默认**——
 * <ol>
 *   <li>用户默认：{@code isDefault=1 且 enabled=1 且已配置 Key}（"每用户至多一个默认"由
 *       服务层保证）；命中则返回该配置，由 {@link LlmProviderRegistry} 构建/缓存客户端；</li>
 *   <li>平台默认：返回 {@code Optional.empty()}，调用方回退到 Spring AI 自动配置的
 *       DeepSeek 客户端——**未配置任何服务的用户，行为与 M13 完全一致**。</li>
 * </ol>
 *
 * <p>钉住场景（{@link #resolveByKey}）：按快照列的服务标识解析；服务已被删除、停用
 * 或未配置 Key 时**回退平台默认**并记 WARN——历史记录的重放不因后续配置变更而失败
 * （《数据库设计》5.13 的快照列语义）。
 */
@Component
public class LlmProviderResolver {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderResolver.class);

    private final LlmProviderMapper llmProviderMapper;

    public LlmProviderResolver(LlmProviderMapper llmProviderMapper) {
        this.llmProviderMapper = llmProviderMapper;
    }

    /** 解析当前用户的默认服务；无可用默认时返回 empty（= 用平台默认） */
    public Optional<LlmProvider> resolveDefault(Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        LlmProvider provider = llmProviderMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getUserId, userId)
                .eq(LlmProvider::getIsDefault, LlmProvider.FLAG_TRUE)
                .eq(LlmProvider::getEnabled, LlmProvider.FLAG_TRUE)
                .last("LIMIT 1"));
        if (provider == null) {
            return Optional.empty();
        }
        if (!provider.hasApiKey()) {
            log.warn("用户默认模型服务未配置 Key，本次回退平台默认：userId={} providerKey={}",
                    userId, provider.getProviderKey());
            return Optional.empty();
        }
        return Optional.of(provider);
    }

    /** 按钉住的服务标识解析（快照列重放）；不可用时返回 empty（= 回退平台默认） */
    public Optional<LlmProvider> resolveByKey(Long userId, String providerKey) {
        if (userId == null || providerKey == null || providerKey.isBlank()) {
            return Optional.empty();
        }
        LlmProvider provider = llmProviderMapper.selectOne(Wrappers.<LlmProvider>lambdaQuery()
                .eq(LlmProvider::getUserId, userId)
                .eq(LlmProvider::getProviderKey, providerKey)
                .eq(LlmProvider::getEnabled, LlmProvider.FLAG_TRUE)
                .last("LIMIT 1"));
        if (provider == null || !provider.hasApiKey()) {
            log.warn("钉住的模型服务不可用（已删除/停用/未配置 Key），本次回退平台默认：userId={} providerKey={}",
                    userId, providerKey);
            return Optional.empty();
        }
        return Optional.of(provider);
    }

}
