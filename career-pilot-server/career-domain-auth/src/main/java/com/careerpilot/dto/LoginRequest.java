package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 登录请求（《API接口设计》3.2）。
 */
public class LoginRequest {

    /** 用户名或邮箱 */
    @NotBlank(message = "账号不能为空")
    private String account;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * 图形验证码标识（M18，《API接口设计》3.2）。
     *
     * <p>**刻意不加 @NotBlank**：`app.captcha.enabled=false` 时既有自动化脚本（tools/ 下
     * 共 30 个）不传验证码字段也必须能登录，而 Bean Validation 是静态的、感知不到运行时开关。
     * 缺失判定由 CaptchaService 在开关打开时做，失败同样返回 40002。
     */
    private String captchaId;

    /** 用户输入的验证码（同上，无校验注解） */
    private String captchaCode;

    public String getAccount() {
        return account;
    }

    public void setAccount(String account) {
        this.account = account;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getCaptchaId() {
        return captchaId;
    }

    public void setCaptchaId(String captchaId) {
        this.captchaId = captchaId;
    }

    public String getCaptchaCode() {
        return captchaCode;
    }

    public void setCaptchaCode(String captchaCode) {
        this.captchaCode = captchaCode;
    }

}
