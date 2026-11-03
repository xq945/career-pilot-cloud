package com.careerpilot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;

import com.careerpilot.common.BusinessException;
import com.careerpilot.config.CaptchaProperties;
import com.careerpilot.dto.CaptchaResponse;

/**
 * 登录图形验证码的生成与校验护栏（M18，《API接口设计》§3.3）。
 *
 * <p>除"图片是合法 PNG"两例外，其余用例都在 **fixed-text 模式**下跑：验证码的设计意图
 * 就是让调用方无法从响应推知答案，测试同样拿不到随机码，故交互逻辑一律用固定文本驱动
 * （生成/校验/一次性/过期/容量/replace 均与答案来源无关）。
 *
 * <p>过期用可推进的 {@link TestClock} 确定性构造，不 sleep。
 */
class CaptchaServiceTest {

    private static final Duration EXPIRY = Duration.ofSeconds(300);

    /** 固定文本用大小写混排，让"忽略大小写"的用例有区分度 */
    private static final String FIXED = "Ab12";

    private final TestClock clock = new TestClock();

    // ---------------------------------------------------------------- 生成

    @Test
    void generate_returnsIdImageAndExpiry() {
        CaptchaService service = service(true, "");

        CaptchaResponse response = service.generate(null);

        assertThat(response.getCaptchaId()).isNotBlank();
        // 图片是**带格式前缀**的 data URI：前端可直接用作 img 的 src，无需自行拼接
        assertThat(response.getImageBase64()).startsWith("data:image/png;base64,");
        assertThat(response.getExpiresIn()).isEqualTo(EXPIRY.toSeconds());
        assertThat(response.isEnabled()).isTrue();
    }

    @Test
    void generate_producesRealPngInRandomMode() {
        // 随机模式下拿不到答案，只能断言图片本身确实是 PNG（magic number）
        CaptchaService service = service(true, "");

        String base64 = service.generate(null).getImageBase64()
                .substring("data:image/png;base64,".length());

        assertThat(Base64.getDecoder().decode(base64))
                .startsWith((byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47);
    }

    @Test
    void generate_stillWorksWhenDisabled() {
        CaptchaService service = service(false, "");

        // 开关只作用于校验；取码照常，前端据此不渲染验证码行，也不会出现破图态
        CaptchaResponse response = service.generate(null);

        assertThat(response.getCaptchaId()).isNotBlank();
        assertThat(response.getImageBase64()).startsWith("data:image/png;base64,");
        assertThat(response.isEnabled()).isFalse();
    }

    // ---------------------------------------------------------------- 校验：通过

    @Test
    void verify_acceptsCorrectCode() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);

        assertThatCode(() -> service.verify(response.getCaptchaId(), FIXED))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_ignoresCase() {
        CaptchaService service = service(true, FIXED);   // 答案是 Ab12
        CaptchaResponse response = service.generate(null);

        // 与 Hutool RandomGenerator.verify 的 equalsIgnoreCase 语义一致
        assertThatCode(() -> service.verify(response.getCaptchaId(), "aB12"))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_toleratesSurroundingWhitespace() {
        CaptchaService service = service(true, FIXED);   // 答案是 Ab12
        CaptchaResponse response = service.generate(null);

        // 移动端输入法常带首尾空格，而验证码字符集本身不含空白，故刻意放宽
        assertThatCode(() -> service.verify(response.getCaptchaId(), "  Ab12 "))
                .doesNotThrowAnyException();
    }

    @Test
    void verify_acceptsCodeJustBeforeExpiry() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);

        clock.advance(EXPIRY.minusSeconds(1));

        assertThatCode(() -> service.verify(response.getCaptchaId(), FIXED))
                .doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 校验：拒绝

    @Test
    void verify_rejectsWrongCode() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);

        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), "zzzz"));
    }

    @Test
    void verify_rejectsExpiredCode() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);

        clock.advance(EXPIRY.plusSeconds(1));

        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), FIXED));
    }

    @Test
    void verify_rejectsUnknownId() {
        CaptchaService service = service(true, FIXED);

        assertCaptchaInvalid(() -> service.verify("no-such-id", FIXED));
    }

    @Test
    void verify_rejectsReuseAfterSuccess() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);
        service.verify(response.getCaptchaId(), FIXED);

        // 一次性：用过的标识立即作废
        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), FIXED));
    }

    @Test
    void verify_rejectsReuseAfterFailure() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);
        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), "zzzz"));

        // 失败同样作废：同一标识不能被用来反复试码
        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), FIXED));
    }

    @Test
    void verify_rejectsBlankFields() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse response = service.generate(null);

        assertCaptchaInvalid(() -> service.verify(null, FIXED));
        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), null));
        assertCaptchaInvalid(() -> service.verify("   ", FIXED));
        assertCaptchaInvalid(() -> service.verify(response.getCaptchaId(), "  "));
    }

    @Test
    void verify_skipsEntirelyWhenDisabled() {
        CaptchaService service = service(false, "");

        // 开关关闭时**两个字段都为空也放行** —— 既有 30 个 tools/ 脚本零改动回归的前提
        assertThatCode(() -> service.verify(null, null)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 刷新与容量

    @Test
    void generate_replaceInvalidatesPreviousId() {
        CaptchaService service = service(true, FIXED);
        CaptchaResponse first = service.generate(null);

        CaptchaResponse second = service.generate(first.getCaptchaId());

        assertThat(second.getCaptchaId()).isNotEqualTo(first.getCaptchaId());
        // 刷新后旧验证码不可继续使用
        assertCaptchaInvalid(() -> service.verify(first.getCaptchaId(), FIXED));
        assertThatCode(() -> service.verify(second.getCaptchaId(), FIXED)).doesNotThrowAnyException();
    }

    @Test
    void evictExpired_removesOnlyExpiredEntries() {
        CaptchaService service = service(true, FIXED);
        service.generate(null);
        clock.advance(EXPIRY.plusSeconds(1));
        service.generate(null);

        assertThat(service.cachedCount()).isEqualTo(2);
        service.evictExpired();
        assertThat(service.cachedCount()).isEqualTo(1);
    }

    @Test
    void generate_evictsOldestWhenCapacityReached() {
        CaptchaService service = service(true, FIXED, 2);
        CaptchaResponse first = service.generate(null);
        // 每次生成之间推进时钟，让三者的到期时刻可区分（淘汰的是"最早过期"的那条）
        clock.advance(Duration.ofSeconds(1));
        CaptchaResponse second = service.generate(null);
        clock.advance(Duration.ofSeconds(1));
        CaptchaResponse third = service.generate(null);

        // 容量上限 2：超限淘汰最早的一条而不是拒绝新请求
        assertThat(service.cachedCount()).isEqualTo(2);
        assertCaptchaInvalid(() -> service.verify(first.getCaptchaId(), FIXED));
        assertThatCode(() -> service.verify(second.getCaptchaId(), FIXED)).doesNotThrowAnyException();
        assertThatCode(() -> service.verify(third.getCaptchaId(), FIXED)).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------- 辅助

    private CaptchaService service(boolean enabled, String fixedText) {
        return service(enabled, fixedText, 10000);
    }

    private CaptchaService service(boolean enabled, String fixedText, int maxSize) {
        return new CaptchaService(
                new CaptchaProperties(enabled, fixedText, (int) EXPIRY.toSeconds(), maxSize), clock);
    }

    /** 验证码类失败一律是同一个错误码与同一个文案（不向调用方区分失败原因） */
    private static void assertCaptchaInvalid(ThrowingCallable callable) {
        assertThatThrownBy(callable)
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getCode()).isEqualTo(40002));
    }

    /** 可推进的测试时钟：过期必须能被确定性地构造出来，而不是靠 sleep */
    private static final class TestClock extends Clock {

        private Instant instant = Instant.parse("2026-10-14T02:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

    }

}
