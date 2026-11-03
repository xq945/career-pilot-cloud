package com.careerpilot.service.optimization.numeric;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 有界中文数字解析器（修复 B）。
 *
 * <p>只支持常见简历写法：0–9999 的整数、十/百/千/万/亿、点后小数，以及
 * 四位逐位年份（二〇二五）。不追求覆盖所有中文数字，无法解析时返回空。
 * 是否允许某个中文数字进入事实校验由 {@code NumericMentionExtractor} 的语境守门决定。
 */
public final class ChineseNumeralParser {

    private static final Map<Character, Integer> DIGITS = new HashMap<>();

    private static final Map<Character, Long> UNITS = new HashMap<>();

    static {
        String[] digitChars = {"零", "〇", "○", "一", "壹", "二", "贰", "两", "俩", "三", "叁",
                "四", "肆", "五", "伍", "六", "陆", "七", "柒", "八", "捌", "九", "玖"};
        int[] digitValues = {0, 0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9};
        for (int i = 0; i < digitChars.length; i++) {
            DIGITS.put(digitChars[i].charAt(0), digitValues[i]);
        }
        UNITS.put('十', 10L);
        UNITS.put('拾', 10L);
        UNITS.put('百', 100L);
        UNITS.put('佰', 100L);
        UNITS.put('千', 1000L);
        UNITS.put('仟', 1000L);
        UNITS.put('万', 10000L);
        UNITS.put('萬', 10000L);
        UNITS.put('亿', 100000000L);
        UNITS.put('億', 100000000L);
    }

    private ChineseNumeralParser() {
    }

    public static Optional<BigDecimal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String value = token.strip();
        int dot = value.indexOf('点');
        if (dot >= 0) {
            String integerPart = value.substring(0, dot);
            String fractionPart = value.substring(dot + 1);
            if (fractionPart.isEmpty()) {
                return Optional.empty();
            }
            Optional<BigDecimal> integer = parseInteger(integerPart.isEmpty() ? "零" : integerPart);
            if (integer.isEmpty() || !isAllDigits(fractionPart)) {
                return Optional.empty();
            }
            StringBuilder fraction = new StringBuilder();
            for (int i = 0; i < fractionPart.length(); i++) {
                fraction.append(DIGITS.get(fractionPart.charAt(i)));
            }
            return Optional.of(new BigDecimal(integer.get().toBigInteger() + "." + fraction));
        }
        return parseInteger(value);
    }

    /** 四位逐位年份：二〇二五、二零二五。 */
    public static Optional<Integer> parseDigitYear(String token) {
        if (token == null || !isAllDigits(token.strip())) {
            return Optional.empty();
        }
        String value = token.strip();
        if (value.length() != 4) {
            return Optional.empty();
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            Integer digit = DIGITS.get(value.charAt(i));
            if (digit == null) {
                return Optional.empty();
            }
            digits.append(digit);
        }
        return Optional.of(Integer.parseInt(digits.toString()));
    }

    public static boolean isAllDigitChars(String token) {
        return token != null && isAllDigits(token.strip());
    }

    /**
     * 口语省略尾单位的歧义写法：{@code 三千五}、{@code 一万五}、{@code 一百五}、{@code 两万三}。
     *
     * <p>这类写法既可能被读成 3500，也可能被逐位读成 3005，业务语境下不具备唯一解释。
     * 有“零”（三千零五）或完整单位（三千五百）的写法是明确的，不在此列；
     * 仅含“十”的十五/二十五也是标准写法，不在此列。
     * 调用方必须把歧义写法排除在数值等价之外，不能当作任何候选值直接匹配。
     */
    public static boolean isAmbiguousShorthand(String token) {
        if (token == null) {
            return false;
        }
        String value = token.strip();
        if (value.isEmpty() || value.indexOf('点') >= 0) {
            return false;
        }
        int lastScale = -1;
        for (int i = 0; i < value.length(); i++) {
            if ("百佰千仟万萬亿億".indexOf(value.charAt(i)) >= 0) {
                lastScale = i;
            }
        }
        if (lastScale < 0) {
            return false;
        }
        String tail = value.substring(lastScale + 1);
        if (tail.isEmpty()) {
            return false;
        }
        boolean sawNonZero = false;
        for (int i = 0; i < tail.length(); i++) {
            Integer digit = DIGITS.get(tail.charAt(i));
            if (digit == null) {
                // 尾部还有单位（如 三千五百），语义完整
                return false;
            }
            if (digit == 0) {
                // 出现“零”：三千零五 是明确的 3005
                return false;
            }
            if (sawNonZero) {
                // 多个独立数字（非法/无法可靠解析）
                return false;
            }
            sawNonZero = true;
        }
        return sawNonZero;
    }

    private static Optional<BigDecimal> parseInteger(String token) {
        String value = token.strip();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        // 全为数字字符：按逐位形式解析（二〇二五）；单字“三”也走这里。
        if (isAllDigits(value)) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < value.length(); i++) {
                Integer digit = DIGITS.get(value.charAt(i));
                if (digit == null) {
                    return Optional.empty();
                }
                digits.append(digit);
            }
            return Optional.of(new BigDecimal(digits.toString()));
        }

        long total = 0;
        long section = 0;
        Long pending = null;
        boolean sawUnit = false;
        boolean sawDigit = false;

        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            Integer digit = DIGITS.get(ch);
            if (digit != null) {
                if (pending != null) {
                    // 连续数字字符且不在全逐位形式中：无法可靠解析
                    return Optional.empty();
                }
                if (digit == 0) {
                    continue;
                }
                pending = (long) digit;
                sawDigit = true;
                continue;
            }
            Long unit = UNITS.get(ch);
            if (unit == null) {
                return Optional.empty();
            }
            sawUnit = true;
            if (unit <= 1000) {
                long base = pending == null ? 1 : pending;
                section += base * unit;
                pending = null;
            }
            else if (unit == 10000) {
                long base = section + (pending == null ? 0 : pending);
                if (base == 0) {
                    base = 1;
                }
                section = base * 10000;
                pending = null;
            }
            else {
                long base = total + section + (pending == null ? 0 : pending);
                if (base == 0) {
                    base = 1;
                }
                total = base * 100000000;
                section = 0;
                pending = null;
            }
        }
        if (!sawDigit && !sawUnit) {
            return Optional.empty();
        }
        long result = total + section + (pending == null ? 0 : pending);
        if (result < 0 || result > 9_999_999_999_999L) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(result));
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!DIGITS.containsKey(value.charAt(i))) {
                return false;
            }
        }
        return !value.isEmpty();
    }
}
