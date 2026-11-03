package com.careerpilot.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 更新模型服务的请求体（《API接口设计》§3A.3）。
 *
 * <p>**字段缺省或空串 = 不修改**（Java DTO 无法区分"未传"与"传 null"，且不提供清空语义
 * ——与 §3.4 昵称的处理同理）；{@code apiKey} 留空即保留原 Key。
 *
 * <p>{@code baseUrl} 对内置服务一律忽略（保持内置定义不变，见 §3A.3）。
 * 校验注解对空串放行（`^(https?://.*)?$`），非空时才要求 http(s) 前缀——具体在服务层归一化后判定。
 */
public class LlmProviderUpdateRequest {

    @Size(max = 100, message = "服务名称不能超过 100 个字符")
    private String displayName;

    @Size(max = 500, message = "接口地址不能超过 500 个字符")
    @Pattern(regexp = "^(https?://.*)?$", message = "接口地址必须以 http:// 或 https:// 开头")
    private String baseUrl;

    @Size(max = 100, message = "模型名不能超过 100 个字符")
    private String model;

    @Size(max = 500, message = "API Key 不能超过 500 个字符")
    private String apiKey;

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
