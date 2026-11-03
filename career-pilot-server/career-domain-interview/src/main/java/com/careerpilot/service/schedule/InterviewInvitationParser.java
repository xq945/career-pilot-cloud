package com.careerpilot.service.schedule;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.DateTimeException;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.careerpilot.entity.InterviewSchedule;

/**
 * 面试邀请的**规则解析器**（M17，《API接口设计》§9A.1）：识别飞书 / 腾讯会议 / Zoom
 * 三种常见格式与通用中文格式，抽取结构化日程字段。
 *
 * <p>成功判定（与 AI 兜底的边界）：**公司 + 岗位 + 面试时间** 三要素齐全才算规则命中
 * （置信度 0.95）；否则返回 null，交由 AI 兜底（0.80）——两者的置信度差表达**信任排序**：
 * 规则是确定性的，AI 是启发式的，兜底结果前端必须提示"请重点核对"。
 *
 * <p>时间语义：绝对日期（`2026-10-20` / `2026年10月20日`）与相对日期（今天 / 明天 / 后天）
 * 均支持；时刻支持 24 小时制（`14:00`）与中文（`下午2点半`）。相对日期依赖"今天"——
 * 通过 {@link Clock} 注入，测试可固定时钟。
 */
@Component
public class InterviewInvitationParser {

    /** 规则命中的置信度（确定性解析） */
    public static final double RULE_CONFIDENCE = 0.95;

    private static final Pattern DATE_PATTERN =
            Pattern.compile("(20\\d{2})\\s*[-/年]\\s*(\\d{1,2})\\s*[-/月]\\s*(\\d{1,2})\\s*日?");

    private static final Pattern RELATIVE_DAY_PATTERN = Pattern.compile("(今天|明天|后天)");

    private static final Pattern TIME_PATTERN =
            Pattern.compile("(上午|下午|晚上|中午)?\\s*(\\d{1,2})\\s*[:：点时]\\s*(\\d{1,2}|半)?\\s*分?");

    private static final Pattern ROUND_PATTERN = Pattern.compile("第\\s*([一二三四五六七八九十\\d]+)\\s*[轮场次]");

    // 公司/岗位名允许含空格（如「Java 后端开发」）——只以标点与换行断句，首尾空白由 firstGroup 的 strip 处理
    private static final Pattern COMPANY_PATTERN =
            Pattern.compile("(?:公司|单位|企业)(?:名称)?[：:]\\s*([^，,。；;、\\n]{1,50})");

    private static final Pattern POSITION_PATTERN =
            Pattern.compile("(?:岗位|职位|职务)(?:名称)?[：:]?\\s*([^，,。；;、\\n]{1,50})");

    private static final Pattern INTERVIEWER_PATTERN =
            Pattern.compile("(?:面试官|面试人|联系人)[：:]\\s*([^\\s，,。；;、\\n]{1,20})");

    private static final Pattern URL_PATTERN =
            Pattern.compile("https?://[^\\s，,。；;、\\n\"'<>]+");

    private static final Pattern TENCENT_MEETING_ID_PATTERN =
            Pattern.compile("(?:会议号|会议\\s*ID)[：:]?\\s*(\\d{6,})");

    private static final Pattern TENCENT_PASSWORD_PATTERN =
            Pattern.compile("(?:密码|入会密码)[：:]?\\s*(\\d{4,})");

    /** 中文数字（单字；"十一"等复合表达暂不支持，回落 null） */
    private static final Map<Character, Integer> CHINESE_NUMBERS = Map.of(
            '一', 1, '二', 2, '三', 3, '四', 4, '五', 5,
            '六', 6, '七', 7, '八', 8, '九', 9, '十', 10);

    /** 规则解析结果（尚未落库） */
    public record ParseOutcome(String companyName, String position, LocalDateTime interviewTime,
                               String interviewType, String meetingLink, Integer roundNumber,
                               String interviewer, String notes) {
    }

    /**
     * 规则解析；三要素（公司 + 岗位 + 时间）不全时返回 null——由调用方走 AI 兜底。
     * 已知局限（文档化）：公司 / 岗位的抽取依赖"公司：/岗位："式关键词标注；
     * 仅出现裸公司名的邀请文本会走 AI 兜底（这正是兜底存在的意义）。
     */
    public ParseOutcome parse(String rawText, Clock clock) {
        if (rawText == null || rawText.isBlank()) {
            return null;
        }
        String text = rawText.strip();
        LocalDateTime interviewTime = resolveDateTime(text, clock);
        String company = firstGroup(COMPANY_PATTERN, text);
        String position = firstGroup(POSITION_PATTERN, text);
        if (interviewTime == null || company == null || position == null) {
            return null;
        }
        return new ParseOutcome(company, position, interviewTime, resolveType(text),
                resolveMeetingLink(text), resolveRound(text), firstGroup(INTERVIEWER_PATTERN, text), null);
    }

    // ---------------------------------------------------------------- 时间

    private LocalDateTime resolveDateTime(String text, Clock clock) {
        LocalDate date = resolveDate(text, clock);
        LocalTime time = resolveTime(text);
        if (date == null || time == null) {
            return null;
        }
        return LocalDateTime.of(date, time);
    }

    private LocalDate resolveDate(String text, Clock clock) {
        Matcher absolute = DATE_PATTERN.matcher(text);
        if (absolute.find()) {
            try {
                return LocalDate.of(Integer.parseInt(absolute.group(1)),
                        Integer.parseInt(absolute.group(2)), Integer.parseInt(absolute.group(3)));
            }
            catch (NumberFormatException | DateTimeException ignored) {
                // 非法日期（如 2026-13-45）：视为未识别，继续尝试相对日期
            }
        }
        Matcher relative = RELATIVE_DAY_PATTERN.matcher(text);
        if (relative.find()) {
            LocalDate today = LocalDate.now(clock);
            return switch (relative.group(1)) {
                case "今天" -> today;
                case "明天" -> today.plusDays(1);
                default -> today.plusDays(2);
            };
        }
        return null;
    }

    private LocalTime resolveTime(String text) {
        Matcher matcher = TIME_PATTERN.matcher(text);
        while (matcher.find()) {
            int hour;
            try {
                hour = Integer.parseInt(matcher.group(2));
            }
            catch (NumberFormatException ex) {
                continue;
            }
            String minuteToken = matcher.group(3);
            int minute = "半".equals(minuteToken) ? 30
                    : (minuteToken == null || minuteToken.isBlank() ? 0 : Integer.parseInt(minuteToken));
            if (hour > 23 || minute > 59) {
                continue;
            }
            String period = matcher.group(1);
            if (("下午".equals(period) || "晚上".equals(period)) && hour < 12) {
                hour += 12;
            }
            if ("中午".equals(period) && hour < 11) {
                hour += 12;
            }
            if ("上午".equals(period) && hour == 12) {
                hour = 0;
            }
            return LocalTime.of(hour, minute);
        }
        return null;
    }

    // ---------------------------------------------------------------- 其余字段

    private String resolveType(String text) {
        if (text.contains("现场") || text.contains("线下") || text.contains("到面")) {
            return InterviewSchedule.TYPE_ONSITE;
        }
        if (text.contains("电话") || text.contains("来电")) {
            return InterviewSchedule.TYPE_PHONE;
        }
        return InterviewSchedule.TYPE_VIDEO;
    }

    private String resolveMeetingLink(String text) {
        Matcher url = URL_PATTERN.matcher(text);
        if (url.find()) {
            String link = url.group();
            return link.length() > 500 ? link.substring(0, 500) : link;
        }
        Matcher meetingId = TENCENT_MEETING_ID_PATTERN.matcher(text);
        if (meetingId.find()) {
            StringBuilder link = new StringBuilder("会议号：").append(meetingId.group(1));
            Matcher password = TENCENT_PASSWORD_PATTERN.matcher(text);
            if (password.find()) {
                link.append(" 密码：").append(password.group(1));
            }
            return link.toString();
        }
        return null;
    }

    private Integer resolveRound(String text) {
        Matcher matcher = ROUND_PATTERN.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String token = matcher.group(1);
        try {
            return Integer.parseInt(token);
        }
        catch (NumberFormatException ignored) {
            Integer chinese = CHINESE_NUMBERS.get(token.charAt(0));
            return chinese;
        }
    }

    private static String firstGroup(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1).strip() : null;
    }

}
