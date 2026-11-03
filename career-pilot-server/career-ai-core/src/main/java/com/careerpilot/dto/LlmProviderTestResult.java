package com.careerpilot.dto;

/**
 * 模型服务连通性测试结果（《API接口设计》§3A.5）。
 *
 * <p>**失败是预期结果**：测试接口不抛 502，一律返回本对象（{@code ok = false} + 可读 message），
 * 由前端就地展示。
 */
public class LlmProviderTestResult {

    private boolean ok;

    /** 成功时为该次调用的实际模型名；失败时为 null */
    private String model;

    /** 成功时的调用耗时（毫秒）；失败时为 null */
    private Long latencyMs;

    /** 失败原因（可读）；成功时为 null */
    private String message;

    public static LlmProviderTestResult success(String model, long latencyMs) {
        LlmProviderTestResult result = new LlmProviderTestResult();
        result.ok = true;
        result.model = model;
        result.latencyMs = latencyMs;
        return result;
    }

    public static LlmProviderTestResult failure(String message) {
        LlmProviderTestResult result = new LlmProviderTestResult();
        result.ok = false;
        result.message = message;
        return result;
    }

    public boolean isOk() {
        return ok;
    }

    public void setOk(boolean ok) {
        this.ok = ok;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Long getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Long latencyMs) {
        this.latencyMs = latencyMs;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

}
