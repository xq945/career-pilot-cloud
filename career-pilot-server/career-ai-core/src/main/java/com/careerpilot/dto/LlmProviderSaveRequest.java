package com.careerpilot.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 新增自定义模型服务的请求体（《API接口设计》§3A.2）。
 *
 * <p>自定义服务一律走 OpenAI 兼容协议（服务端固定 {@code kind = OPENAI}），
 * {@code providerKey} 由服务端生成（custom-1、custom-2…），不接受前端指定。
 */
public class LlmProviderSaveRequest {

    @NotBlank(message = "服务名称不能为空")
    @Size(max = 100, message = "服务名称不能超过 100 个字符")
    private String displayName;

    @NotBlank(message = "接口地址不能为空")
    @Size(max = 500, message = "接口地址不能超过 500 个字符")
    @Pattern(regexp = "^https?://.*", message = "接口地址必须以 http:// 或 https:// 开头")
    private String baseUrl;

    @NotBlank(message = "模型名不能为空")
    @Size(max = 100, message = "模型名不能超过 100 个字符")
    private String model;

    @NotBlank(message = "API Key 不能为空")
    @Size(max = 500, message = "API Key 不能超过 500 个字符")
    private String apiKey;

    /** 可选，0–2；缺省 0.30 */
    @DecimalMin(value = "0.0", message = "temperature 不能小于 0")
    @DecimalMax(value = "2.0", message = "temperature 不能大于 2")
    private BigDecimal temperature;

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
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

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public BigDecimal getTemperature() {
        return temperature;
    }

    public void setTemperature(BigDecimal temperature) {
        this.temperature = temperature;
    }

}
