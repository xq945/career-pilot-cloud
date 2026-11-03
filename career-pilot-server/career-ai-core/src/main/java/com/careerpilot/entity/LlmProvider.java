package com.careerpilot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 模型服务配置，对应《数据库设计》中的 llm_provider（V1.8 新增，M14）。
 *
 * <p>每用户一份（归属校验同全项目惯例：查询必带 user_id）；内置 DeepSeek/Kimi/GLM
 * 三家由服务层在首次访问列表时懒初始化（{@code builtin = 1}，不可删除），
 * 自定义服务（{@code kind = OPENAI}）由用户增删改。
 *
 * <p>**API Key 只存密文**（《系统架构设计》§7.4）：{@code apiKeyCiphertext} + {@code apiKeyNonce}
 * 为 AES-GCM 加密产物；实体与 DTO 均不得携带明文 Key，接口只回显掩码。
 *
 * <p>**快照列**（《数据库设计》5.13）：会话与优化记录创建时把本表的 {@code providerKey}
 * 与模型名写入各自的快照列（不建外键），此后本行的修改与删除不影响历史记录。
 */
@TableName("llm_provider")
public class LlmProvider {

    /** 构建方式：原生 DeepSeek ChatModel（保留 thinking 关闭的现状行为） */
    public static final String KIND_DEEPSEEK = "DEEPSEEK";

    /** 构建方式：OpenAI 兼容协议（Kimi/GLM/自定义） */
    public static final String KIND_OPENAI = "OPENAI";

    /** 布尔列取值（TINYINT，同 resume.status 的惯例） */
    public static final int FLAG_TRUE = 1;

    public static final int FLAG_FALSE = 0;

    /** 默认温度（未单独配置时） */
    public static final BigDecimal DEFAULT_TEMPERATURE = new BigDecimal("0.30");

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 用户内唯一的服务标识（deepseek/kimi/glm/custom-N），钉入会话与优化的快照列 */
    private String providerKey;

    private String displayName;

    /** DEEPSEEK / OPENAI（见常量） */
    private String kind;

    private String baseUrl;

    private String model;

    private BigDecimal temperature;

    /** API Key 密文（AES-GCM，Base64）；从未配置时为 null */
    private String apiKeyCiphertext;

    /** AES-GCM 随机 nonce（12 字节，Base64） */
    private String apiKeyNonce;

    /** 1 = 当前用户默认服务（每用户至多一个，服务层保证） */
    private Integer isDefault;

    /** 1 = 内置服务（不可删除） */
    private Integer builtin;

    /** 1 = 启用 */
    private Integer enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 是否已配置 API Key（密文存在即视为已配置） */
    public boolean hasApiKey() {
        return apiKeyCiphertext != null && !apiKeyCiphertext.isBlank();
    }

    /*
     * 三个布尔列的语义读法。命名刻意**不用 is/get 前缀**：与 getBuiltin()/getEnabled()/
     * getIsDefault() 构成 JavaBeans 歧义 getter 时，MyBatis 的反射内省会直接抛
     * "Illegal overloaded getter method with ambiguous type"（TINYINT 列在 INSERT 时触发），
     * 且 Mockito 单测覆盖不到——详见 M14 验证记录的「期间修正的问题」。
     */

    /** 是否内置服务（TINYINT = 1） */
    public boolean builtinFlag() {
        return builtin != null && builtin == FLAG_TRUE;
    }

    /** 是否启用（TINYINT = 1） */
    public boolean enabledFlag() {
        return enabled != null && enabled == FLAG_TRUE;
    }

    /** 是否当前用户的默认服务（TINYINT = 1） */
    public boolean defaultFlag() {
        return isDefault != null && isDefault == FLAG_TRUE;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getProviderKey() {
        return providerKey;
    }

    public void setProviderKey(String providerKey) {
        this.providerKey = providerKey;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

    public String getApiKeyCiphertext() {
        return apiKeyCiphertext;
    }

    public void setApiKeyCiphertext(String apiKeyCiphertext) {
        this.apiKeyCiphertext = apiKeyCiphertext;
    }

    public String getApiKeyNonce() {
        return apiKeyNonce;
    }

    public void setApiKeyNonce(String apiKeyNonce) {
        this.apiKeyNonce = apiKeyNonce;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Integer isDefault) {
        this.isDefault = isDefault;
    }

    public Integer getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Integer builtin) {
        this.builtin = builtin;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
