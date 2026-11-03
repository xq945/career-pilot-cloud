package com.careerpilot.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
 * <p>存储为进程内 {@link ConcurrentHashMap}：一次性、有有效期、有容量上限，并有定时清理。
 * 不引入 Redis——验证码是短生命周期且丢失无害（重新获取即可），无需跨实例共享。
 *
 * <p>**校验一律先删除后比对**：只要某个 captchaId 被用于一次登录尝试，无论成功还是失败
 * 都从缓存移除。这杜绝了同一标识被反复试码，并天然覆盖"重复提交"与"刷新后旧码"
 * 两类场景；前端在失败时会自动换图，因此严格一次性不牺牲体验。
 *
 * <p>答案与图片只在响应中返回给请求方，**不写日志**（《系统架构设计》§7）。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

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

    private final Clock clock;

    private final Map<String, CaptchaEntry> store = new ConcurrentHashMap<>();

    @Autowired
    public CaptchaService(CaptchaProperties properties) {
        this(properties, Clock.systemDefaultZone());
    }

    /** 供单元测试注入固定时钟（生产走上面那个构造器） */
    CaptchaService(CaptchaProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- 生成

    /**
     * 生成一张新验证码。
     *
     * <p>{@code enabled=false} 时**照常生成返回**——前端登录页的验证码 UI 在任何配置下
     * 都能正常显示与刷新，不会出现破图态；开关只作用于登录时的校验。
     *
     * @param replaceId 可选：刷新场景下传当前 captchaId，生成新码的同时立即作废它，
     *                  满足"验证码刷新后旧验证码不可继续使用"（《API接口设计》§3.3）
     */
    public CaptchaResponse generate(String replaceId) {
        if (replaceId != null && !replaceId.isBlank()) {
            store.remove(replaceId);
        }
        evictIfFull();

        LineCaptcha captcha = createCaptcha();
        // getCode() 会惰性触发 createCode()（Hutool 的 createLineCaptcha 只构造、不绘图）
        String code = captcha.getCode();
        String image = captcha.getImageBase64Data();

        String captchaId = UUID.randomUUID().toString();
        store.put(captchaId, new CaptchaEntry(code, clock.instant().plusSeconds(properties.expireSeconds())));

        CaptchaResponse response = new CaptchaResponse();
        response.setCaptchaId(captchaId);
        response.setImageBase64(image);
        response.setExpiresIn(properties.expireSeconds());
        // 开关状态随响应下发：前端据此决定是否渲染验证码行（见 CaptchaResponse#enabled）
        response.setEnabled(properties.isEnabled());
        return response;
    }

    /**
     * 随机码与固定文本码走**同一条绘制管线**（仅生成器不同），因此测试态与生产态的
     * 图片形态、校验行为完全一致，差别只在答案是否可知。
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
     *
     * <p>开关关闭时直接放行——**包括两个字段都为空的情况**，这是既有自动化脚本
     * （tools/verify 与 tools/e2e 共 30 个）以 {@code APP_CAPTCHA_ENABLED=false} 回归的前提。
     */
    public void verify(String captchaId, String captchaCode) {
        if (!properties.isEnabled()) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }

        // 先取出并删除：一次性消费，成功与失败都不再保留该标识
        CaptchaEntry entry = store.remove(captchaId);
        if (entry == null) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }
        if (entry.expiresAt().isBefore(clock.instant())) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }
        // 比对忽略大小写（与 Hutool RandomGenerator.verify 的语义一致），并容忍首尾空白
        // —— 移动端输入法常带空格，而验证码字符集本身不含空白。
        if (!entry.code().equalsIgnoreCase(captchaCode.trim())) {
            throw BusinessException.captchaInvalid(INVALID_MESSAGE);
        }
    }

    // ---------------------------------------------------------------- 清理与容量

    /**
     * 清理过期项。cron 走配置（默认每 5 分钟），样板同 {@code ScheduleStatusUpdater}。
     *
     * <p>注意 cron 简写（星号斜杠）**不可写进 JavaDoc 块注释**，会提前终止注释。
     */
    @Scheduled(cron = "${app.captcha.cleanup-cron:0 */5 * * * *}")
    public void evictExpired() {
        Instant now = clock.instant();
        int before = store.size();
        store.entrySet().removeIf(entry -> entry.getValue().expiresAt().isBefore(now));
        int removed = before - store.size();
        if (removed > 0) {
            log.debug("验证码缓存清理：移除 {} 条过期项，剩余 {} 条", removed, store.size());
        }
    }

    /** 容量兜底：超限时先清过期项，仍超限则淘汰最早过期的一条（不拒绝新请求） */
    private void evictIfFull() {
        if (store.size() < properties.maxSize()) {
            return;
        }
        evictExpired();
        if (store.size() < properties.maxSize()) {
            return;
        }
        Comparator<Map.Entry<String, CaptchaEntry>> byExpiry =
                Comparator.comparing(entry -> entry.getValue().expiresAt());
        store.entrySet().stream().min(byExpiry).ifPresent(oldest -> store.remove(oldest.getKey()));
        log.warn("验证码缓存已达容量上限 {}，淘汰最早过期的一条", properties.maxSize());
    }

    /** 当前缓存条数：包私有，供单测断言清理与容量淘汰的实际效果（无需为此暴露给生产代码） */
    int cachedCount() {
        return store.size();
    }

    /** 一条验证码：答案与到期时刻 */
    private record CaptchaEntry(String code, Instant expiresAt) {
    }

    /**
     * 固定文本生成器（**仅 automation 测试路径使用**，由 {@code app.captcha.fixed-text} 启用）。
     *
     * <p>只改"生成什么"，绘制仍走 Hutool 原有管线；本服务的比对也不经过它——
     * 用的是与随机码同一条 {@code equalsIgnoreCase} 判定，故校验行为不受影响。
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
