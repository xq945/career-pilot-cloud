package com.careerpilot.dto;

import java.math.BigDecimal;

/**
 * 模型服务响应对象（《API接口设计》§3A.1/§3A.2/§3A.3 共用）。
 *
 * <p>**只回显掩码**：{@code maskedApiKey} 为"前 3 位 + *** + 后 3 位"，未配置时为 null；
 * 该对象在任何路径下都不得携带明文或密文 Key（《系统架构设计》§7.4）。
 */
public class LlmProviderView {

    private Long id;

    private String providerKey;

    private String displayName;

    /** DEEPSEEK / OPENAI */
    private String kind;

    private String baseUrl;

    private String model;

    private BigDecimal temperature;

    /** 掩码（如 sk-***abc）；null = 未配置 Key */
    private String maskedApiKey;

    private Boolean isDefault;

    private Boolean builtin;

    private Boolean enabled;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getMaskedApiKey() {
        return maskedApiKey;
    }

    public void setMaskedApiKey(String maskedApiKey) {
        this.maskedApiKey = maskedApiKey;
    }

    public Boolean getIsDefault() {
        return isDefault;
    }

    public void setIsDefault(Boolean isDefault) {
        this.isDefault = isDefault;
    }

    public Boolean getBuiltin() {
        return builtin;
    }

    public void setBuiltin(Boolean builtin) {
        this.builtin = builtin;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

}
