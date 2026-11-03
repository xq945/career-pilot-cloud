package com.careerpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 登录图形验证码配置（M18，《API接口设计》§3.3）。
 *
 * <p>{@code fixedText} 是**仅供自动化测试**的开关：非空时所有验证码图片都绘制该固定文本，
 * 使 Node 脚本（tools/verify、tools/e2e）能够填对答案——脚本无法识别图片内容。
 * **生产必须留空**，否则验证码形同虚设。它只改变"画什么字"，校验逻辑本身不受影响，
 * 仍然逐字比对。
 *
 * <p>配置不合法的 fail-fast 校验放在构造器：延续 MatchScoringProperties 与提示词加载的惯例，
 * 让配置错误在启动时暴露而不是首次登录才失败。
 *
 * <p>注册方式为 {@code @EnableConfigurationProperties}（见启动类），**不加 @Component**：
 * 组件扫描注册的配置类走构造器自动装配，会把构造参数当成待注入的 bean 而启动失败。
 */
@ConfigurationProperties(prefix = "app.captcha")
public class CaptchaProperties {

    private final boolean enabled;

    private final String fixedText;

    private final int expireSeconds;

    private final int maxSize;

    public CaptchaProperties(Boolean enabled, String fixedText, Integer expireSeconds, Integer maxSize) {
        if (enabled == null) {
            throw new IllegalStateException("app.captcha.enabled 未配置（M18）");
        }
        if (expireSeconds == null || expireSeconds <= 0) {
            throw new IllegalStateException("app.captcha.expire-seconds 必须为正整数（M18）");
        }
        if (maxSize == null || maxSize <= 0) {
            throw new IllegalStateException("app.captcha.max-size 必须为正整数（M18）");
        }
        this.enabled = enabled;
        this.fixedText = fixedText == null ? "" : fixedText;
        this.expireSeconds = expireSeconds;
        this.maxSize = maxSize;
    }

    /** 登录时是否校验验证码；false 时跳过校验（供既有自动化脚本零改动回归） */
    public boolean isEnabled() {
        return enabled;
    }

    /** 仅测试用的固定验证码文本；空串表示走随机生成 */
    public String fixedText() {
        return fixedText;
    }

    /** 验证码有效期（秒） */
    public int expireSeconds() {
        return expireSeconds;
    }

    /** 本地缓存容量上限（条） */
    public int maxSize() {
        return maxSize;
    }

}
