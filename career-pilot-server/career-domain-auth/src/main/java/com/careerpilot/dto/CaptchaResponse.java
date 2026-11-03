package com.careerpilot.dto;

/**
 * 图形验证码响应（M18，《API接口设计》§3.3）。
 */
public class CaptchaResponse {

    /** 验证码唯一标识，登录时随请求原样回传 */
    private String captchaId;

    /**
     * 验证码图片：**带文件格式的 Base64 数据 URI**（`data:image/png;base64,...`），
     * 可直接用于 `<img>` 的 src，前端无需拼接前缀。
     */
    private String imageBase64;

    /** 有效期（秒），供前端提示与自动刷新参考 */
    private long expiresIn;

    /**
     * 登录时是否会校验验证码（即 `app.captcha.enabled`）。
     *
     * <p>下发它是为了让前端在开关关闭时**不渲染验证码行**：登录页与"没有验证码"时逐字一致，
     * 既有自动化脚本（它们看不到图片、也不会填验证码）的 DOM 与元素索引因而不受任何影响。
     */
    private boolean enabled;

    public String getCaptchaId() {
        return captchaId;
    }

    public void setCaptchaId(String captchaId) {
        this.captchaId = captchaId;
    }

    public String getImageBase64() {
        return imageBase64;
    }

    public void setImageBase64(String imageBase64) {
        this.imageBase64 = imageBase64;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

}
