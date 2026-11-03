package com.careerpilot.service.optimization.coverage;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 覆盖校验的文本口径（修复 C）。
 *
 * <p>三种口径必须分开，不能混用：
 * <ul>
 *   <li>{@link #matchNormalize}：定位 change 的 before/after，保留标点与表格竖线，只去空白/行首 Markdown；</li>
 *   <li>{@link #canonical}：块等价比较，只保留字母/汉字/数字，消除格式差异；</li>
 *   <li>{@link #contentWords}：内容词新颖度，提取 CJK 连续串与英文 token，供“是否出现原文没有的词”判断。</li>
 * </ul>
 *
 * <p>本类不访问数据库、不调用模型、不输出正文。
 */
final class CoverageText {

    private static final Pattern LINE_PREFIX = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*|[-*+·•]\\s+|\\d+[.)]\\s+)+");

    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fa5]{2,}");

    private static final Pattern ENGLISH = Pattern.compile("[A-Za-z][A-Za-z0-9+#.]*");

    private CoverageText() {
    }

    static String nfkc(String text) {
        return text == null ? "" : Normalizer.normalize(text, Normalizer.Form.NFKC);
    }

    /** 定位 change 用：保留标点与 |，去行首 Markdown、去空白、小写。 */
    static String matchNormalize(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = nfkc(text)
                .replace('\r', '\n')
                .replace('，', ',').replace('。', '.').replace('；', ';').replace('：', ':')
                .replace('、', ',').replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace('—', '-').replace('–', '-').replace('～', '~')
                .replace('·', '-').replace('•', '-');
        value = LINE_PREFIX.matcher(value).replaceAll("");
        value = value.replaceAll("\\s+", "");
        return value.toLowerCase(Locale.ROOT);
    }

    /** 块等价/格式比较用：只保留字母、汉字、数字（全半角已 NFKC）。 */
    static String canonical(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = nfkc(text).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                result.appendCodePoint(cp);
            }
        });
        return result.toString();
    }

    /** 内容词：CJK 连续串（2 字以上）+ 英文 token；小写、去重。 */
    static Set<String> contentWords(String text) {
        Set<String> words = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return words;
        }
        String value = nfkc(text).toLowerCase(Locale.ROOT);
        Matcher cjk = CJK_RUN.matcher(value);
        while (cjk.find()) {
            words.add(cjk.group());
        }
        Matcher english = ENGLISH.matcher(value);
        while (english.find()) {
            String token = english.group();
            if (token.length() >= 2) {
                words.add(token);
            }
        }
        return words;
    }

    static boolean hasCjk(String text) {
        if (text == null) {
            return false;
        }
        return text.codePoints().anyMatch(cp -> cp >= 0x4e00 && cp <= 0x9fff);
    }

    static boolean isCjk(char ch) {
        return ch >= '一' && ch <= '鿿';
    }
}
