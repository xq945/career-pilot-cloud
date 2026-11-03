package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.careerpilot.ai.LlmApiKeyCipher;
import com.careerpilot.ai.LlmProviderRegistry;
import com.careerpilot.common.BusinessException;
import com.careerpilot.config.LlmProperties;
import com.careerpilot.dto.LlmProviderSaveRequest;
import com.careerpilot.dto.LlmProviderTestResult;
import com.careerpilot.dto.LlmProviderUpdateRequest;
import com.careerpilot.dto.LlmProviderView;
import com.careerpilot.entity.LlmProvider;
import com.careerpilot.mapper.LlmProviderMapper;

/**
 * 模型服务管理的业务规则（M14，《API接口设计》§3A）：
 * 懒初始化内置三家、自定义服务加密落库与掩码、字段"缺省=不修改"、
 * 内置不可删 / 默认不可删 / 未配置 Key 不可设为默认、切换默认清理旧默认并失效缓存。
 */
class LlmProviderServiceTest {

    private LlmProviderMapper mapper;

    private LlmProviderRegistry registry;

    private LlmApiKeyCipher cipher;

    private LlmProviderService service;

    @BeforeEach
    void setUp() {
        mapper = mock(LlmProviderMapper.class);
        registry = mock(LlmProviderRegistry.class);
        cipher = new LlmApiKeyCipher(new LlmProperties("test-master-key", null, null));
        service = new LlmProviderService(mapper, cipher, registry);
    }

    @Test
    @DisplayName("首次列表懒初始化内置三家：deepseek 为默认、均 builtin 且未配置 Key")
    void initializesBuiltinsOnFirstAccess() {
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.selectList(any())).thenReturn(List.of());

        service.list(1L);

        ArgumentCaptor<LlmProvider> captor = ArgumentCaptor.forClass(LlmProvider.class);
        verify(mapper, times(3)).insert(captor.capture());
        List<LlmProvider> inserted = captor.getAllValues();
        assertEquals(List.of("deepseek", "kimi", "glm"),
                inserted.stream().map(LlmProvider::getProviderKey).toList());
        assertTrue(inserted.stream().allMatch(LlmProvider::builtinFlag));
        assertTrue(inserted.stream().allMatch(LlmProvider::enabledFlag));
        assertFalse(inserted.stream().anyMatch(LlmProvider::hasApiKey));
        assertTrue(inserted.get(0).defaultFlag());
        assertFalse(inserted.get(1).defaultFlag());
        assertFalse(inserted.get(2).defaultFlag());
    }

    @Test
    @DisplayName("已有记录时不重复初始化")
    void skipsInitializationWhenRecordsExist() {
        when(mapper.selectCount(any())).thenReturn(3L);
        when(mapper.selectList(any())).thenReturn(List.of());

        service.list(1L);

        verify(mapper, never()).insert(any(LlmProvider.class));
    }

    @Test
    @DisplayName("创建自定义服务：密文落库、providerKey 自动生成、响应只含掩码")
    void createEncryptsKeyAndReturnsMask() {
        when(mapper.selectCount(any())).thenReturn(3L);
        when(mapper.selectList(any())).thenReturn(List.of());

        LlmProviderSaveRequest request = new LlmProviderSaveRequest();
        request.setDisplayName("我的 Kimi");
        request.setBaseUrl("https://api.moonshot.cn/v1");
        request.setModel("kimi-latest");
        request.setApiKey("sk-abcdefghijklmn");

        LlmProviderView view = service.create(1L, request);

        ArgumentCaptor<LlmProvider> captor = ArgumentCaptor.forClass(LlmProvider.class);
        verify(mapper).insert(captor.capture());
        LlmProvider saved = captor.getValue();
        assertEquals("custom-1", saved.getProviderKey());
        assertEquals(LlmProvider.KIND_OPENAI, saved.getKind());
        assertNotEquals("sk-abcdefghijklmn", saved.getApiKeyCiphertext());
        assertNotNull(saved.getApiKeyNonce());
        assertEquals("sk-***lmn", view.getMaskedApiKey());
        assertEquals(Boolean.FALSE, view.getBuiltin());
        assertEquals(Boolean.TRUE, view.getEnabled());
        assertEquals(Boolean.FALSE, view.getIsDefault());
    }

    @Test
    @DisplayName("更新：apiKey 空白不修改密文；内置服务 baseUrl 被忽略；其余字段生效")
    void updateKeepsBlankFieldsAndIgnoresBuiltinBaseUrl() {
        LlmProvider builtin = provider("deepseek", true, false);
        builtin.setApiKeyCiphertext("existing-cipher");
        builtin.setApiKeyNonce("existing-nonce");
        when(mapper.selectOne(any())).thenReturn(builtin);

        LlmProviderUpdateRequest request = new LlmProviderUpdateRequest();
        request.setBaseUrl("https://should-be-ignored.example.com");
        request.setModel("deepseek-v4");
        request.setApiKey("   ");

        service.update(1L, 5L, request);

        assertEquals("existing-cipher", builtin.getApiKeyCiphertext());
        assertEquals("existing-nonce", builtin.getApiKeyNonce());
        assertEquals("https://api.deepseek.com", builtin.getBaseUrl());
        assertEquals("deepseek-v4", builtin.getModel());
        verify(mapper).updateById(builtin);
        verify(registry).evict(1L);
    }

    @Test
    @DisplayName("内置服务不可删除")
    void builtinCannotBeDeleted() {
        when(mapper.selectOne(any())).thenReturn(provider("deepseek", true, false));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(1L, 5L));
        assertEquals(40000, ex.getCode());
        assertEquals("内置服务不可删除", ex.getMessage());
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("当前默认服务不可删除（先切换）")
    void defaultCannotBeDeleted() {
        when(mapper.selectOne(any())).thenReturn(provider("custom-1", false, true));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(1L, 5L));
        assertEquals(40000, ex.getCode());
        verify(mapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("未配置 Key 的服务不能设为默认")
    void setDefaultRequiresApiKey() {
        when(mapper.selectOne(any())).thenReturn(provider("custom-1", false, false));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.setDefault(1L, 5L));
        assertEquals(40000, ex.getCode());
        assertEquals("该服务尚未配置 API Key，无法设为默认", ex.getMessage());
    }

    @Test
    @DisplayName("切换默认：清除旧默认、写入新默认、缓存定向失效")
    void setDefaultClearsOldAndEvictsCache() {
        LlmProvider target = provider("custom-1", false, false);
        target.setApiKeyCiphertext("cipher");
        target.setApiKeyNonce("nonce");
        when(mapper.selectOne(any())).thenReturn(target);

        service.setDefault(1L, 5L);

        // 清旧默认（条件更新）
        verify(mapper).update(any(LlmProvider.class), any());
        // 写入新默认
        verify(mapper).updateById(target);
        assertTrue(target.defaultFlag());
        verify(registry).evict(1L);
    }

    @Test
    @DisplayName("未配置 Key 的服务连通性测试直接返回失败，不发起调用")
    void testWithoutApiKeyReturnsFailure() {
        when(mapper.selectOne(any())).thenReturn(provider("kimi", true, false));

        LlmProviderTestResult result = service.test(1L, 5L);

        assertFalse(result.isOk());
        assertEquals("尚未配置 API Key", result.getMessage());
        verify(registry, never()).clientForProvider(any(LlmProvider.class));
    }

    private static LlmProvider provider(String providerKey, boolean builtin, boolean isDefault) {
        LlmProvider provider = new LlmProvider();
        provider.setId(5L);
        provider.setUserId(1L);
        provider.setProviderKey(providerKey);
        provider.setDisplayName(providerKey);
        provider.setKind(LlmProvider.KIND_DEEPSEEK);
        provider.setBaseUrl("https://api.deepseek.com");
        provider.setModel("deepseek-flash");
        provider.setTemperature(new BigDecimal("0.30"));
        provider.setBuiltin(builtin ? LlmProvider.FLAG_TRUE : LlmProvider.FLAG_FALSE);
        provider.setIsDefault(isDefault ? LlmProvider.FLAG_TRUE : LlmProvider.FLAG_FALSE);
        provider.setEnabled(LlmProvider.FLAG_TRUE);
        return provider;
    }

}
