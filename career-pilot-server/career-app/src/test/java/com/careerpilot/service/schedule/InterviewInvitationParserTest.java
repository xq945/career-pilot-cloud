package com.careerpilot.service.schedule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 面试邀请规则解析（M17，《API接口设计》§9A.1）：三平台格式与通用中文格式的关键路径。
 *
 * 相对日期使用**固定 Clock**（2026-10-15 10:00 +08:00）——断言与环境无关；
 * "三要素不全返回 null"是规则与 AI 兜底的边界，单独钉住。
 */
class InterviewInvitationParserTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(Instant.parse("2026-10-15T02:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final InterviewInvitationParser parser = new InterviewInvitationParser();

    @Test
    @DisplayName("飞书格式：公司/岗位/时间/链接/轮次齐全")
    void parsesFeishuStyle() {
        String text = """
                【飞书】面试邀请
                公司：示例科技
                岗位：Java 后端开发
                时间：2026-10-20 14:00
                第2轮
                会议链接：https://meeting.feishu.cn/abc123
                """;

        var outcome = parser.parse(text, FIXED_CLOCK);

        assertNotNull(outcome);
        assertEquals("示例科技", outcome.companyName());
        assertEquals("Java 后端开发", outcome.position());
        assertEquals(LocalDateTime.of(2026, 10, 20, 14, 0), outcome.interviewTime());
        assertEquals(2, outcome.roundNumber());
        assertEquals("https://meeting.feishu.cn/abc123", outcome.meetingLink());
        assertEquals("VIDEO", outcome.interviewType());
    }

    @Test
    @DisplayName("腾讯会议格式：斜杠日期 + 会议号与密码拼装；现场关键词 → ONSITE")
    void parsesTencentStyle() {
        String text = """
                公司：云启科技
                岗位：后端开发工程师
                面试时间：2026/11/03 15:30
                会议号：123456789
                密码：6677
                现场面试
                """;

        var outcome = parser.parse(text, FIXED_CLOCK);

        assertNotNull(outcome);
        assertEquals(LocalDateTime.of(2026, 11, 3, 15, 30), outcome.interviewTime());
        assertEquals("会议号：123456789 密码：6677", outcome.meetingLink());
        assertEquals("ONSITE", outcome.interviewType());
    }

    @Test
    @DisplayName("相对日期与中文时刻：明天下午3点半 → 次日 15:30")
    void parsesRelativeChineseTime() {
        String text = """
                公司：未来科技
                岗位：Java 工程师
                时间：明天下午3点半
                """;

        var outcome = parser.parse(text, FIXED_CLOCK);

        assertNotNull(outcome);
        assertEquals(LocalDateTime.of(2026, 10, 16, 15, 30), outcome.interviewTime());
    }

    @Test
    @DisplayName("中文数字轮次（第三轮 → 3）与电话关键词（→ PHONE）")
    void parsesChineseRoundAndPhone() {
        String text = """
                公司：电信云
                岗位：后端开发
                时间：2026-10-18 09:30
                第三轮
                电话面试
                """;

        var outcome = parser.parse(text, FIXED_CLOCK);

        assertNotNull(outcome);
        assertEquals(3, outcome.roundNumber());
        assertEquals("PHONE", outcome.interviewType());
    }

    @Test
    @DisplayName("三要素不全 → 返回 null（交由 AI 兜底）")
    void returnsNullWhenIncomplete() {
        assertNull(parser.parse("明天下午3点面试，腾讯会议", FIXED_CLOCK));
        assertNull(parser.parse("公司：示例科技\n时间：2026-10-20 14:00", FIXED_CLOCK));
        assertNull(parser.parse("", FIXED_CLOCK));
        assertNull(parser.parse(null, FIXED_CLOCK));
    }

    @Test
    @DisplayName("非法绝对日期回落相对日期：同一文本含「明天」时仍可解析")
    void fallsBackToRelativeDateWhenAbsoluteInvalid() {
        String text = """
                公司：示例科技
                岗位：后端开发
                时间：2026-13-45 明天 14:00
                """;

        var outcome = parser.parse(text, FIXED_CLOCK);

        assertNotNull(outcome);
        assertEquals(LocalDateTime.of(2026, 10, 16, 14, 0), outcome.interviewTime());
    }

}
