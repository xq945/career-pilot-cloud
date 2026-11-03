package com.careerpilot.service;

import java.time.Clock;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.CaptchaProperties;
import com.careerpilot.dto.CaptchaResponse;

import cn.hutool.captcha.CaptchaUtil;
import cn.hutool.captcha.LineCaptcha;
import cn.hutool.captcha.generator.CodeGenerator;

/**
 * 登录图形验证码的生成与校验（M18，《API接口设计》§3.3）。
 *
 * <p>阶段 4 起存储可配置：
 * <ul>
 *   <li>{@code app.captcha.store=redis}（部署默认）：多实例共享，TTL 由 Redis 负责，
 *       一次性消费用 GETDEL；</li>
 *   <li>{@code local}（单机/测试）：保留原有的进程内 Map、容量上限与定时清理。</li>
 * </ul>
 *
 * <p>**校验一律先取出并删除后比对**：同一 captchaId 无论成功失败都立即作废，
 * 杜绝反复试码；前端失败时自动换图。
 */
@Service
public class CaptchaService {

    /** 图片尺寸：120×40 在登录卡片（380px）内与输入框并排后仍然清晰可读 */
    private static final int WIDTH = 120;

    private static final int HEIGHT = 40;

    /** 随机码位数；固定文本（测试用）不受本值约束，由配置自行给出 */
    private static final int CODE_COUNT = 4;

    private static final int LINE_COUNT = 20;

    /**
     * 失败提示统一文案：不区分"输入错误""已过期""已使用""未获取"，
     * 与 {@link BusinessException#captchaInvalid} 的单一错误码配套。
     */
    private static final String INVALID_MESSAGE = "验证码错误或已失效，请重新获取";

    private final CaptchaProperties properties;

    private final CaptchaStore store;

    @Autowired
    public CaptchaService(CaptchaProperties properties,
                          @Value("${app.captcha.store:local}") String storeType,
                          ObjectProvider<StringRedisTemplate> redisProvider) {
        this(properties, createStore(storeType, properties, redisProvider));
    }

    /** 供单元测试注入固定时钟（生产走上面那个构造器，本地存储） */
    CaptchaService(CaptchaProperties properties, Clock clock) {
        this(properties, new LocalCaptchaStore(properties.maxSize(), clock));
    }

    private CaptchaService(CaptchaProperties properties, CaptchaStore store) {
        this.properties = properties;
        this.store = store;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 生成一张新验证码；{@code enabled=false} 时照常生成返回，开关只作用于校验。
     *
     * @param replaceId 可选：刷新场景下传当前 captchaId，生成新码的同时立即作废它
     */
    public CaptchaResponse generate(String replaceId) {
        if (replaceId != null && !replaceId.isBlank()) {
            store.remove(replaceId);
        }

        LineCaptcha captcha = createCaptcha();
        String code = captcha.getCode();
        String image = captcha.getImageBase64Data();

        String captchaId = UUID.randomUUID().toString();
        store.save(captchaId, code, properties.expireSeconds());

        CaptchaResponse response = new CaptchaResponse();
        response.setCaptchaId(captchaId);
        response.setImageBase64(image);
        response.setExpiresIn(properties.expireSeconds());
        response.setEnabled(properties.isEnabled());
        return response;
    }

    /**
     * 随机码与固定文本码走同一条绘制管线（仅生成器不同）。
     */
    private LineCaptcha createCaptcha() {
        String fixedText = properties.fixedText();
        if (fixedText.isBlank()) {
            return CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, CODE_COUNT, LINE_COUNT);
        }
        return CaptchaUtil.createLineCaptcha(WIDTH, HEIGHT, new FixedTextGenerator(fixedText), LINE_COUNT);
    }

    // ---------------------------------------------------------------- 校验

    /**
     * 校验登录请求携带的验证码；任何失败都抛同一个 40002 与同一句文案。
     * 开关关闭时直接放行（既有自动化脚本以 {@code APP_CAPTCHA_ENABLED=false} 回归的前提）。
     */
    public void verify(String captchaId, String captchaCode) {
        if (!properties.isEnabled()) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }

        // 原子取出并删除：一次性消费，成功与失败都不再保留该标识
        String expected = store.take(captchaId);
        if (expected == null) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }
        // 比对忽略大小写，并容忍首尾空白（移动端输入法常带空格）
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- 清理

    /** 本地存储需要定时清理；Redis 模式为 no-op（TTL 自动） */
    @Scheduled(cron = "${app.captcha.cleanup-cron:0 */5 * * * *}")
    public void evictExpired() {
        store.evictExpired();
    }

    /** 当前缓存条数：仅本地存储有意义，供单测断言 */
    int cachedCount() {
        return store.size();
    }

    private static CaptchaStore createStore(String storeType, CaptchaProperties properties,
                                            ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("redis".equalsIgnoreCase(storeType)) {
            StringRedisTemplate redis = redisProvider.getIfAvailable();
            if (redis == null) {
                throw new IllegalStateException(
                        "app.captcha.store=redis，但未配置 Redis（spring.data.redis.*）");
            }
            return new RedisCaptchaStore(redis);
        }
        return new LocalCaptchaStore(properties.maxSize(), Clock.systemDefaultZone());
    }

    /**
     * 固定文本生成器（仅 automation 测试路径使用）。
     */
    private record FixedTextGenerator(String text) implements CodeGenerator {

        @Override
        public String generate() {
            return text;
        }

        @Override
        public boolean verify(String code, String userInputCode) {
            return userInputCode != null && !userInputCode.isBlank() && code.equalsIgnoreCase(userInputCode);
        }

    }
}
