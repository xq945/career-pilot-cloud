package com.careerpilot.ai;

/**
 * JSON 字符串中**未转义引号 / 裸控制字符**的本地修复（纯函数，M14）。
 *
 * <p>背景：模型输出合法 JSON 时偶尔会漏转义字符串内部的引号
 * （如 {@code {"analysis": "他说"很好"然后..."}}），或从简历里复制制表符/换行
 * （{@code CTRL-CHAR code 9}），导致反序列化失败。这类损坏是**机械可修复**的：
 * 在不破坏结构的前提下补转义。
 *
 * <p>修复策略（单遍状态机扫描）：跟踪"是否在字符串内 / 是否处于转义态"；
 * 字符串内的裸引号若其后（跳过空白）紧跟 {@code , } ] :} 或结束，视为字符串结束符，
 * 否则判定为未转义的内容引号并补 {@code \"}；字符串内的裸控制字符按 JSON 规则转义。
 *
 * <p>本类**只做机械转义修复**、不做其他猜测；修复后仍解析失败时，由 {@link AiCallTemplate}
 * 走修复型重试（《系统架构设计》§11 V8 的结论：本地修复优先，避免为可机械修复的损坏
 * 多花一次模型调用）。
 */
public final class JsonStringRepair {

    private JsonStringRepair() {
    }

    /**
     * 修复字符串内未转义的引号；内容为空或无法确定时原样返回。
     * 无裸引号时返回值与原值逐字符相同（调用方据此判断"是否发生了修复"）。
     */
    public static String repairUnescapedQuotes(String content) {
        if (content == null || content.isBlank()) {
            return content;
        }
        StringBuilder repaired = new StringBuilder(content.length() + 16);
        boolean inString = false;
        boolean escaping = false;
        for (int i = 0; i < content.length(); i++) {
            char ch = content.charAt(i);
            if (!inString) {
                if (ch == '"') {
                    inString = true;
                }
                repaired.append(ch);
                continue;
            }

            if (escaping) {
                repaired.append(ch);
                escaping = false;
                continue;
            }
            if (ch == '\\') {
                repaired.append(ch);
                escaping = true;
                continue;
            }
            if (ch < 0x20) {
                repaired.append(controlEscape(ch));
                continue;
            }
            if (ch == '"') {
                if (isLikelyJsonStringTerminator(content, i + 1)) {
                    inString = false;
                    repaired.append(ch);
                } else {
                    repaired.append("\\\"");
                }
                continue;
            }
            repaired.append(ch);
        }
        return repaired.toString();
    }

    /** 字符串内的裸控制字符转成 JSON 合法转义。 */
    private static String controlEscape(char ch) {
        return switch (ch) {
            case '\t' -> "\\t";
            case '\n' -> "\\n";
            case '\r' -> "\\r";
            case '\b' -> "\\b";
            case '\f' -> "\\f";
            default -> String.format("\\u%04x", (int) ch);
        };
    }

    /** 引号之后（跳过空白）若紧跟 , } ] : 或已到末尾，视为字符串的结束引号 */
    private static boolean isLikelyJsonStringTerminator(String content, int start) {
        for (int i = start; i < content.length(); i++) {
            char next = content.charAt(i);
            if (Character.isWhitespace(next)) {
                continue;
            }
            return next == ',' || next == '}' || next == ']' || next == ':';
        }
        return true;
    }

}
