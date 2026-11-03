package com.careerpilot.service.optimization.numeric;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerpilot.service.optimization.numeric.NumericLexicons.MetricAlias;
import com.careerpilot.service.optimization.numeric.NumericLexicons.MetricRule;
import com.careerpilot.service.optimization.numeric.NumericLexicons.StatisticMatch;
import com.careerpilot.service.optimization.numeric.NumericLexicons.UnitAlias;
import com.careerpilot.service.optimization.numeric.NumericMention.Direction;
import com.careerpilot.service.optimization.numeric.NumericMention.Kind;
import com.careerpilot.service.optimization.numeric.NumericMention.Qualifier;
import com.careerpilot.service.optimization.numeric.NumericMention.UnitFamily;

/**
 * 修复 B 的数字提及提取器。
 *
 * <p>先识别日期、版本、不支持表达并标记消费区间，再扫描普通数字/中文数字；
 * 然后按“最近后缀标签 + 前一个提及 boundEnd + 标点/连词边界”绑定指标、对象、
 * 主体、方向、限定词和统计口径。任何无法可靠绑定的上下文保留为空，由
 * {@link NumericFactGuard} 按三态规则处理，不做猜测。
 */
public final class NumericMentionExtractor {

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern URL = Pattern.compile(
            "(?:https?://|www\\.)\\S+", Pattern.CASE_INSENSITIVE);

    private static final Pattern LONG_DIGITS = Pattern.compile("\\d{10,}");

    private static final Pattern LINE_PREFIX = Pattern.compile(
            "^(\\s*(?:#{1,6}\\s*|[-*+]\\s+|\\d+[.)]\\s+)+)");

    private static final Pattern DATE_CN_YEAR = Pattern.compile(
            "([零〇○一二两三四五六七八九]{4})\\s*年"
                    + "(?:\\s*([零〇一二两三四五六七八九]{1,2})\\s*月"
                    + "(?:\\s*([零〇一二两三四五六七八九]{1,3})\\s*[日号])?)?");

    private static final Pattern DATE_CN_ARABIC = Pattern.compile(
            "(\\d{4})\\s*年(?:\\s*(\\d{1,2})\\s*月(?:\\s*(\\d{1,2})\\s*[日号])?)?");

    private static final Pattern DATE_NUM = Pattern.compile(
            "(\\d{4})\\s*[./-]\\s*(\\d{1,2})(?:\\s*[./-]\\s*(\\d{1,2})(?![0-9]))?");

    private static final Pattern DATE_JIE = Pattern.compile("(\\d{4})\\s*届");

    private static final Pattern VERSION_KEYWORD = Pattern.compile(
            "(?:版本|version)\\s*(?:[:：]\\s*)?v?(\\d+(?:\\.\\d+)+)", Pattern.CASE_INSENSITIVE);

    private static final Pattern VERSION_DOTTED = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9.+#]*?)\\s+v?(\\d+(?:\\.\\d+)+)");

    private static final Pattern VERSION_INT = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z0-9.+#]*?)\\s+v?(\\d{1,3})(?![0-9A-Za-z])");

    private static final Pattern VERSION_EMBEDDED = Pattern.compile(
            "(?<![A-Za-z0-9])([A-Za-z][A-Za-z+#]{1,})(\\d{1,4}(?:\\.\\d+)*)(?![0-9A-Za-z])");

    private static final Pattern PERCENT_CN = Pattern.compile(
            "百分之\\s*([零〇一二两三四五六七八九十百]+)");

    /** 中文数字 token（含“点”小数）；与普通数字一起用于识别范围 */
    private static final String CN_NUMERAL_TOKEN =
            "[零〇○一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+"
                    + "(?:点[零〇一二两三四五六七八九]+)?";

    private static final String RANGE_OPERAND =
            "(?:\\d[\\d,]*(?:\\.\\d+)?|" + CN_NUMERAL_TOKEN + ")";

    /** 范围表达（含中文数词）不参与数值等价，只允许整行未变 */
    private static final Pattern UNSUPPORTED_RANGE = Pattern.compile(
            "(?<![\\d.])" + RANGE_OPERAND + "\\s*[-~至到]\\s*" + RANGE_OPERAND + "(?![\\d.])");

    /** 3.5k 一类简写在系统中不归一，明确标记为不支持 */
    private static final Pattern UNSUPPORTED_K_SUFFIX = Pattern.compile(
            "(?<![\\d.])\\d[\\d,]*(?:\\.\\d+)?\\s*[kK](?![A-Za-z])");

    private static final Pattern UNSUPPORTED_FRACTION = Pattern.compile("\\d+\\s*/\\s*\\d+");

    private static final Pattern UNSUPPORTED_CN_FRACTION = Pattern.compile(
            "[零〇一二两三四五六七八九十百千万两]+分之[零〇一二两三四五六七八九十百千万两]+");

    private static final Pattern UNSUPPORTED_WORD = Pattern.compile(
            "若干|数十|十余|数百|数千|数万|上万|近万|三成|八成|半年|半个月|半小时"
                    + "|三分之一|二分之一");

    private static final Pattern GENERIC_METRIC_PREFIX = Pattern.compile(
            "^([\\u4e00-\\u9fa5A-Za-z]{1,8}(?:率|量|时间|耗时|延迟|成本|费用|收入|利润|速度|比例|占比))");

    private static final Pattern GENERIC_NUMBER = Pattern.compile(
            "(\\d{1,3}(?:[,]\\d{3})+(?:\\.\\d+)?"
                    + "|\\d{1,3}(?: \\d{3})+(?:\\.\\d+)?"
                    + "|\\d+\\.\\d+"
                    + "|\\d+"
                    + "|[零〇○一二两三四五六七八九十百千万亿壹贰叁肆伍陆柒捌玖拾佰仟]+"
                    + "(?:点[零〇一二两三四五六七八九]+)?)");

    private static final List<String> FILLERS = List.of(
            "约为", "共计", "总共", "累计", "达到", "为", "是", "达", "共", "有", "的", "地");

    private static final Set<String> AMBIGUITY_MARKERS = Set.of("分别", "均", "各自");

    /** Markdown 标题行（在原始 NFKC 文本上识别，prepareText 会掩码掉 # 前缀） */
    private static final Pattern HEADING_LINE = Pattern.compile("^\\s*#{1,6}\\s+(.+)$");

    /** 独立的纯项目标题行：只允许“项目/课题/竞赛/实习/工作 + 短标识”，避免把正文当标题 */
    private static final Pattern PLAIN_TITLE = Pattern.compile(
            "^(?:项目|课题|竞赛|实习|工作)\\s*(?:[A-Za-z]{1,3}|[一二三四五六七八九十]{1,3})$");

    /** 行内项目标识：项目 A / 项目A / 项目一；不把“项目 3 个”里的数字当项目名 */
    private static final Pattern PROJECT_TOKEN = Pattern.compile(
            "项目\\s*(?:[A-Za-z]{1,3}|[一二三四五六七八九十]{1,3})(?![A-Za-z0-9])");

    /** 上下文中只做稳定性归一，不参与事实比较的日期片段 */
    private static final Pattern CONTEXT_DATE = Pattern.compile(
            "(?:\\d{4}\\s*年\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*[日号])?"
                    + "|\\d{4}\\s*[./-]\\s*\\d{1,2}(?:\\s*[./-]\\s*\\d{1,2})?(?![\\d])"
                    + "|\\d{4}\\s*年(?!\\s*\\d)"
                    + "|\\d{4}\\s*届)");

    /** 行内标签分句分隔符（不含冒号：标签本身位于冒号左侧） */
    private static final String LABEL_SEPARATORS = ",;!?，；！？、|";

    public List<NumericMention> extract(String text) {
        String prepared = prepareText(text);
        if (prepared.isEmpty()) {
            return List.of();
        }
        boolean[] consumed = new boolean[prepared.length()];
        List<Draft> drafts = new ArrayList<>();

        extractDates(prepared, consumed, drafts);
        extractVersions(prepared, consumed, drafts);
        extractChinesePercent(prepared, consumed, drafts);
        extractUnsupported(prepared, consumed, drafts);
        extractGenericNumbers(prepared, consumed, drafts);

        drafts.sort(Comparator.comparingInt(d -> d.start));
        List<Draft> filtered = new ArrayList<>();
        int lastEnd = -1;
        for (Draft draft : drafts) {
            if (draft.start >= lastEnd && draft.end > draft.start) {
                filtered.add(draft);
                lastEnd = draft.end;
            }
        }

        bind(prepared, filtered);
        applyLineSignatures(prepared, filtered);
        applyContextSignatures(text, prepared, filtered);

        List<NumericMention> mentions = new ArrayList<>(filtered.size());
        for (Draft d : filtered) {
            mentions.add(new NumericMention(d.kind, d.value, d.unit, d.unitFamily,
                    d.metricKey, d.objectKey, d.subjectKey, d.direction, d.qualifier,
                    d.statisticKey, d.start, d.end, d.lineSignature, d.contextSignature,
                    d.ambiguousBinding, d.unsupportedRaw, d.supported));
        }
        return List.copyOf(mentions);
    }

    // ---------------------------------------------------------------- 预处理

    static String prepareText(String text) {
        if (text == null) {
            return "";
        }
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC)
                .replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch == '–' || ch == '—' || ch == '−' || ch == '－') {
                builder.append('-');
            }
            else if (ch == '～') {
                builder.append('~');
            }
            else {
                builder.append(ch);
            }
        }
        String normalized = builder.toString();
        StringBuilder masked = new StringBuilder(normalized);
        mask(masked, URL, masked.toString());
        mask(masked, EMAIL, masked.toString());
        mask(masked, LONG_DIGITS, masked.toString());

        // 行首 Markdown 列表编号/标题符号替换为等长空格，保留偏移
        int lineStart = 0;
        while (lineStart <= masked.length()) {
            int lineEnd = masked.indexOf("\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = masked.length();
            }
            String line = masked.substring(lineStart, lineEnd);
            Matcher matcher = LINE_PREFIX.matcher(line);
            if (matcher.find()) {
                for (int i = lineStart + matcher.start(); i < lineStart + matcher.end(); i++) {
                    masked.setCharAt(i, ' ');
                }
            }
            if (lineEnd == masked.length()) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return masked.toString();
    }

    private static void mask(StringBuilder builder, Pattern pattern, String source) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            for (int i = matcher.start(); i < matcher.end(); i++) {
                builder.setCharAt(i, ' ');
            }
        }
    }

    // ---------------------------------------------------------------- 日期/版本/不支持表达

    private void extractDates(String text, boolean[] consumed, List<Draft> drafts) {
        extractDatePattern(text, consumed, drafts, DATE_CN_YEAR, true);
        extractDatePattern(text, consumed, drafts, DATE_CN_ARABIC, false);
        extractDatePattern(text, consumed, drafts, DATE_NUM, false);
        extractDatePattern(text, consumed, drafts, DATE_JIE, false);
    }

    private void extractDatePattern(String text, boolean[] consumed, List<Draft> drafts,
                                    Pattern pattern, boolean chineseYear) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (overlaps(consumed, matcher.start(), matcher.end())) {
                continue;
            }
            DateValue date = parseDate(matcher, chineseYear);
            if (date == null) {
                continue;
            }
            int end = date.end;
            String value = date.value;
            int cursor = skipSpaces(text, end);
            if (cursor < text.length() && isRangeSeparator(text.charAt(cursor))) {
                int after = skipSpaces(text, cursor + 1);
                String open = openEndAt(text, after);
                boolean consumedSeparator = false;
                if (open == null && text.charAt(cursor) == '至'
                        && after < text.length() && text.charAt(after) == '今') {
                    open = "至今";
                    consumedSeparator = true;
                }
                if (open != null) {
                    value = value + "..OPEN";
                    end = after + (consumedSeparator ? 1 : open.length());
                }
                else {
                    DateValue second = dateAt(text, after);
                    if (second != null && second.start == after) {
                        value = value + ".." + second.value;
                        end = second.end;
                    }
                }
            }
            markConsumed(consumed, matcher.start(), end);
            Draft draft = new Draft();
            draft.kind = Kind.DATE;
            draft.value = value;
            draft.unit = "";
            draft.unitFamily = UnitFamily.DATE;
            draft.start = matcher.start();
            draft.end = end;
            draft.supported = true;
            drafts.add(draft);
        }
    }

    private DateValue dateAt(String text, int start) {
        for (Pattern pattern : List.of(DATE_CN_YEAR, DATE_CN_ARABIC, DATE_NUM, DATE_JIE)) {
            Matcher matcher = pattern.matcher(text);
            matcher.region(start, text.length());
            if (!matcher.lookingAt()) {
                continue;
            }
            DateValue value = parseDate(matcher, pattern == DATE_CN_YEAR);
            if (value != null && value.start == start) {
                return value;
            }
        }
        return null;
    }

    private DateValue parseDate(Matcher matcher, boolean chineseYear) {
        try {
            int year = chineseYear
                    ? ChineseNumeralParser.parseDigitYear(matcher.group(1)).orElse(-1)
                    : Integer.parseInt(matcher.group(1));
            Integer month = null;
            Integer day = null;
            if (matcher.groupCount() >= 2 && matcher.group(2) != null) {
                month = chineseYear
                        ? ChineseNumeralParser.parse(matcher.group(2)).map(BigDecimal::intValue).orElse(null)
                        : Integer.parseInt(matcher.group(2));
            }
            if (matcher.groupCount() >= 3 && matcher.group(3) != null) {
                day = chineseYear
                        ? ChineseNumeralParser.parse(matcher.group(3)).map(BigDecimal::intValue).orElse(null)
                        : Integer.parseInt(matcher.group(3));
            }
            if (year < 1000 || year > 9999 || (month != null && (month < 1 || month > 12))
                    || (day != null && (day < 1 || day > 31))) {
                return null;
            }
            String value;
            if (month == null) {
                value = String.valueOf(year);
            }
            else if (day == null) {
                value = String.format(Locale.ROOT, "%04d-%02d", year, month);
            }
            else {
                value = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day);
            }
            return new DateValue(matcher.start(), matcher.end(), value);
        }
        catch (RuntimeException ex) {
            return null;
        }
    }

    private void extractVersions(String text, boolean[] consumed, List<Draft> drafts) {
        extractVersionPattern(text, consumed, drafts, VERSION_KEYWORD, 1, true);
        extractVersionPattern(text, consumed, drafts, VERSION_DOTTED, 2, false);
        extractVersionPattern(text, consumed, drafts, VERSION_INT, 2, false);
        extractVersionPattern(text, consumed, drafts, VERSION_EMBEDDED, 2, false);
    }

    private void extractVersionPattern(String text, boolean[] consumed, List<Draft> drafts,
                                       Pattern pattern, int numberGroup, boolean keyword) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(numberGroup);
            int end = matcher.end(numberGroup);
            if (overlaps(consumed, start, end) || unitAfter(text, end) != null) {
                continue;
            }
            if (matcher.group(numberGroup).contains(".")) {
                // 版本号自身可以包含点；这里不做额外拆分。
            }
            if (!keyword && matcher.group(1) != null
                    && matcher.group(1).length() < 2
                    && !matcher.group(1).contains("+")
                    && !matcher.group(1).contains("#")) {
                continue;
            }
            String anchor;
            if (keyword) {
                anchor = "版本";
            }
            else if (pattern == VERSION_EMBEDDED) {
                anchor = matcher.group(1).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
            }
            else {
                VersionAnchor versionAnchor = versionAnchor(text, start);
                if (!versionAnchor.meaningful()) {
                    continue;
                }
                anchor = versionAnchor.key();
            }
            if (anchor.isEmpty()) {
                continue;
            }
            markConsumed(consumed, start, end);
            Draft draft = new Draft();
            draft.kind = Kind.VERSION;
            draft.value = matcher.group(numberGroup).replaceFirst("^v", "");
            draft.unit = "";
            draft.unitFamily = UnitFamily.VERSION;
            draft.subjectKey = anchor;
            draft.start = start;
            draft.end = end;
            draft.supported = true;
            drafts.add(draft);
        }
    }

    private VersionAnchor versionAnchor(String text, int numberStart) {
        List<String> tokens = new ArrayList<>();
        int cursor = numberStart - 1;
        while (cursor >= 0 && tokens.size() < 3) {
            while (cursor >= 0 && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
                cursor--;
            }
            int end = cursor + 1;
            while (cursor >= 0 && isAsciiWordChar(text.charAt(cursor))) {
                cursor--;
            }
            if (end == cursor + 1) {
                break;
            }
            String token = text.substring(cursor + 1, end);
            tokens.add(0, token);
            int before = cursor;
            while (before >= 0 && (text.charAt(before) == ' ' || text.charAt(before) == '\t'
                    || text.charAt(before) == '-')) {
                before--;
            }
            if (before < 0 || !isAsciiWordChar(text.charAt(before))) {
                break;
            }
            cursor = before;
        }
        if (tokens.isEmpty()) {
            return new VersionAnchor("", false);
        }
        boolean allStop = tokens.stream()
                .map(t -> t.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]", ""))
                .filter(t -> !t.isEmpty())
                .allMatch(NumericLexicons.VERSION_ANCHOR_STOP_WORDS::contains);
        if (allStop) {
            return new VersionAnchor("", false);
        }
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        StringBuilder merged = new StringBuilder();
        for (String token : tokens) {
            String key = token.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+#]", "");
            if (!key.isEmpty()) {
                keys.add(key);
                merged.append(key);
            }
        }
        if (merged.length() > 0) {
            keys.add(merged.toString());
        }
        return new VersionAnchor(String.join("|", keys), true);
    }

    private void extractChinesePercent(String text, boolean[] consumed, List<Draft> drafts) {
        Matcher matcher = PERCENT_CN.matcher(text);
        while (matcher.find()) {
            if (overlaps(consumed, matcher.start(), matcher.end())) {
                continue;
            }
            Optional<BigDecimal> value = ChineseNumeralParser.parse(matcher.group(1));
            if (value.isEmpty()) {
                continue;
            }
            markConsumed(consumed, matcher.start(), matcher.end());
            Draft draft = new Draft();
            draft.kind = Kind.PERCENT;
            draft.value = canonical(value.get());
            draft.unit = "%";
            draft.unitFamily = UnitFamily.PERCENT;
            draft.start = matcher.start();
            draft.end = matcher.end();
            draft.supported = true;
            drafts.add(draft);
        }
    }

    private void extractUnsupported(String text, boolean[] consumed, List<Draft> drafts) {
        addUnsupported(text, consumed, drafts, UNSUPPORTED_CN_FRACTION);
        addUnsupported(text, consumed, drafts, UNSUPPORTED_FRACTION);
        addUnsupported(text, consumed, drafts, UNSUPPORTED_RANGE);
        addUnsupported(text, consumed, drafts, UNSUPPORTED_K_SUFFIX);
        addUnsupported(text, consumed, drafts, UNSUPPORTED_WORD);
    }

    private void addUnsupported(String text, boolean[] consumed, List<Draft> drafts,
                                Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            if (overlaps(consumed, matcher.start(), matcher.end())) {
                continue;
            }
            markConsumed(consumed, matcher.start(), matcher.end());
            Draft draft = new Draft();
            draft.kind = Kind.UNSUPPORTED;
            draft.value = normalizeRaw(text.substring(matcher.start(), matcher.end()));
            draft.unit = "";
            draft.unitFamily = UnitFamily.UNSUPPORTED;
            draft.start = matcher.start();
            draft.end = matcher.end();
            draft.unsupportedRaw = draft.value;
            draft.supported = false;
            drafts.add(draft);
        }
    }

    // ---------------------------------------------------------------- 普通数字

    private void extractGenericNumbers(String text, boolean[] consumed, List<Draft> drafts) {
        Matcher matcher = GENERIC_NUMBER.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (overlaps(consumed, start, end)) {
                continue;
            }
            String token = matcher.group();
            boolean chinese = isChineseNumeral(token);
            boolean negative = false;
            if (!chinese) {
                if (start > 0 && (isAsciiLetter(text.charAt(start - 1))
                        || Character.isDigit(text.charAt(start - 1)))) {
                    continue;
                }
                if (end < text.length() && Character.isDigit(text.charAt(end))) {
                    continue;
                }
                if (end < text.length() && isAsciiLetter(text.charAt(end))
                        && NumericLexicons.unitAt(text, end) == null) {
                    continue;
                }
                if (start > 0 && (text.charAt(start - 1) == '-' || text.charAt(start - 1) == '−')
                        && !consumed[start - 1]
                        && (start == 1 || (!Character.isDigit(text.charAt(start - 2))
                        && !isAsciiLetter(text.charAt(start - 2))))) {
                    start--;
                    negative = true;
                }
            }

            int cursor = skipSpaces(text, end);
            BigDecimal value;
            if (chinese) {
                Optional<BigDecimal> parsed = ChineseNumeralParser.parse(token);
                if (parsed.isEmpty()) {
                    continue;
                }
                if (ChineseNumeralParser.isAmbiguousShorthand(token)) {
                    addChineseAmbiguousDraft(consumed, drafts, start, end, token);
                    continue;
                }
                value = parsed.get();
                if (!chineseCanBeFact(text, matcher.start(), matcher.end(), token, cursor)) {
                    continue;
                }
                // “一点五万”：中文 token 在“点”小数处结束，数量级在 token 之外
                while (cursor < text.length() && isMagnitude(text.charAt(cursor))) {
                    value = value.multiply(magnitude(text.charAt(cursor)));
                    cursor++;
                    cursor = skipSpaces(text, cursor);
                }
            }
            else {
                try {
                    String digits = token.replace(",", "").replace(" ", "");
                    value = new BigDecimal(digits);
                }
                catch (NumberFormatException ex) {
                    continue;
                }
                while (cursor < text.length() && isMagnitude(text.charAt(cursor))) {
                    value = value.multiply(magnitude(text.charAt(cursor)));
                    cursor++;
                    cursor = skipSpaces(text, cursor);
                }
            }

            if (negative) {
                value = value.negate();
            }
            UnitAlias unit = NumericLexicons.unitAt(text, cursor);
            int endWithUnit = cursor + (unit == null ? 0 : unit.alias().length());
            if (unit == null) {
                endWithUnit = cursor;
            }
            String unitCanonical = unit == null ? "" : unit.rule().canonical();
            UnitFamily family = unit == null ? UnitFamily.PLAIN : unit.rule().family();

            Draft draft = new Draft();
            draft.kind = kindFor(family);
            draft.value = canonical(value);
            draft.unit = unitCanonical;
            draft.unitFamily = family;
            draft.start = start;
            draft.end = endWithUnit;
            draft.supported = true;
            drafts.add(draft);
            markConsumed(consumed, start, endWithUnit);
        }
    }

    /** 中文口语歧义写法（三千五）不进入数值等价，只保留原文供“整行未变”判断 */
    private void addChineseAmbiguousDraft(boolean[] consumed, List<Draft> drafts,
                                          int start, int end, String token) {
        markConsumed(consumed, start, end);
        Draft draft = new Draft();
        draft.kind = Kind.UNSUPPORTED;
        draft.value = normalizeRaw(token);
        draft.unit = "";
        draft.unitFamily = UnitFamily.UNSUPPORTED;
        draft.start = start;
        draft.end = end;
        draft.unsupportedRaw = draft.value;
        draft.supported = false;
        drafts.add(draft);
    }

    private boolean chineseCanBeFact(String text, int start, int end, String token, int cursor) {
        for (String word : NumericLexicons.CHINESE_FALSE_POSITIVES) {
            int wordStart = Math.max(0, start - 2);
            int index = text.indexOf(word, wordStart);
            if (index >= 0 && index <= start && index + word.length() >= end) {
                return false;
            }
        }
        if ("千万".equals(token)) {
            int next = skipSpaces(text, end);
            if (next < text.length() && "要别不注小".indexOf(text.charAt(next)) >= 0) {
                return false;
            }
        }
        UnitAlias unitAlias = NumericLexicons.unitAt(text, cursor);
        if (unitAlias != null) {
            if ("一".equals(token) && "家".equals(unitAlias.rule().canonical())) {
                int afterUnit = skipSpaces(text, cursor + unitAlias.alias().length());
                for (String word : List.of("公司", "企业", "单位", "银行", "学校", "医院", "店铺")) {
                    if (text.startsWith(word, afterUnit)) {
                        return false;
                    }
                }
            }
            return true;
        }
        if (token.length() == 1) {
            return false;
        }
        if (token.contains("点")) {
            return true;
        }
        if (token.chars().anyMatch(c -> "十百千万亿拾佰仟萬億".indexOf(c) >= 0)) {
            return true;
        }
        return token.length() == 4 && ChineseNumeralParser.isAllDigitChars(token);
    }

    // ---------------------------------------------------------------- 绑定

    private void bind(String text, List<Draft> drafts) {
        int previousClauseStart = -1;
        int boundEnd = -1;
        for (int i = 0; i < drafts.size(); i++) {
            Draft draft = drafts.get(i);
            if (draft.kind == Kind.DATE || draft.kind == Kind.VERSION || draft.kind == Kind.UNSUPPORTED) {
                draft.boundEnd = draft.end;
                boundEnd = draft.end;
                previousClauseStart = clauseStart(text, draft.start);
                continue;
            }
            int clauseStart = clauseStart(text, draft.start);
            int clauseEnd = clauseEnd(text, draft.end);
            if (clauseStart != previousClauseStart) {
                boundEnd = clauseStart;
                previousClauseStart = clauseStart;
            }
            int leftStart = Math.max(clauseStart, boundEnd);
            String leftRaw = text.substring(leftStart, draft.start);
            int rightEnd = clauseEnd;
            for (int j = i + 1; j < drafts.size(); j++) {
                Draft next = drafts.get(j);
                if (next.start >= clauseEnd) {
                    break;
                }
                if (next.start >= draft.end) {
                    rightEnd = Math.min(rightEnd, next.start);
                    break;
                }
            }
            String rightRaw = text.substring(draft.end, Math.max(draft.end, rightEnd));

            bindOne(text, draft, leftRaw, rightRaw, clauseStart);

            draft.boundEnd = draft.boundEnd <= draft.end ? draft.end : draft.boundEnd;
            boundEnd = draft.boundEnd;
        }
    }

    private void bindOne(String text, Draft draft, String leftRaw, String rightRaw,
                         int clauseStart) {
        String left = trimEnd(leftRaw);
        String right = trimStart(rightRaw);
        int suffixConsumed = rightRaw.length() - right.length();

        // 前缀 QPS/TPS 形式：QPS 1000
        if (draft.unit.isEmpty()) {
            String upper = left.toUpperCase(Locale.ROOT);
            if (upper.endsWith("QPS") || upper.endsWith("TPS")) {
                String key = upper.endsWith("QPS") ? "QPS" : "TPS";
                draft.unit = key;
                draft.unitFamily = UnitFamily.RATE;
                draft.kind = Kind.RATE;
                draft.metricKey = key;
                left = trimEnd(left.substring(0, left.length() - 3));
            }
        }

        // 前缀限定词与填充词（循环处理“约为”）
        boolean changed = true;
        while (changed && !left.isEmpty()) {
            changed = false;
            String qualifierAlias = NumericLexicons.qualifierAliasAtSuffix(left);
            if (qualifierAlias != null) {
                mergeQualifier(draft, NumericLexicons.QUALIFIERS.get(qualifierAlias));
                left = trimEnd(left.substring(0, left.length() - qualifierAlias.length()));
                changed = true;
                continue;
            }
            for (String filler : FILLERS) {
                if (left.endsWith(filler)) {
                    left = trimEnd(left.substring(0, left.length() - filler.length()));
                    changed = true;
                    break;
                }
            }
        }
        // 前缀方向：转化率提升 / 成本降低
        String directionAlias = directionAliasAtSuffix(left);
        if (directionAlias != null) {
            mergeDirection(draft, NumericLexicons.DIRECTIONS.get(directionAlias));
            left = trimEnd(left.substring(0, left.length() - directionAlias.length()));
        }
        StatisticMatch statistic = findStatistic(left);
        if (statistic != null) {
            mergeStatistic(draft, statistic.key());
            if (left.endsWith(statistic.alias())) {
                left = trimEnd(left.substring(0, left.length() - statistic.alias().length()));
            }
        }
        if (left.contains("分别") || left.contains("各自") || left.contains("均")) {
            draft.ambiguousBinding = true;
        }

        ObjectLabel deferredObject = null;
        MetricLabel prefixMetric = null;
        GenericMetric prefixGeneric = null;
        int labelStart = left.length();
        if (draft.unitFamily == UnitFamily.COUNT || draft.unit.isEmpty()) {
            deferredObject = objectSuffix(left);
        }
        if (deferredObject != null) {
            labelStart = deferredObject.start();
        }
        else {
            prefixMetric = metricSuffix(left);
            if (prefixMetric != null) {
                mergeMetric(draft, prefixMetric.key(), prefixMetric.subject());
                labelStart = prefixMetric.start();
            }
            else {
                prefixGeneric = genericMetricSuffix(left);
                if (prefixGeneric != null) {
                    mergeMetric(draft, prefixGeneric.key(), prefixGeneric.subject());
                    labelStart = prefixGeneric.start();
                }
                else if (draft.unitFamily == UnitFamily.COUNT || draft.unit.isEmpty()) {
                    String object = NumericLexicons.objectPrefix(left);
                    if (object != null) {
                        deferredObject = new ObjectLabel(object,
                                left.length() - object.length(), object.length());
                        labelStart = deferredObject.start();
                    }
                }
            }
        }
        String subjectFromMetric = prefixMetric == null ? "" : prefixMetric.subject();

        // 分隔符携带：用户数：3 万 / 用户数（3 万）
        if (labelStart == left.length() && left.isBlank() && draft.metricKey.isEmpty()) {
            String carry = carryLabel(text, clauseStart);
            if (carry != null && !carry.isBlank()) {
                String carryTrimmed = trimEnd(carry);
                if (draft.unitFamily == UnitFamily.COUNT || draft.unit.isEmpty()) {
                    ObjectLabel carryObject = objectSuffix(carryTrimmed);
                    if (carryObject == null) {
                        String object = NumericLexicons.objectPrefix(carryTrimmed);
                        if (object != null) {
                            carryObject = new ObjectLabel(object,
                                    carryTrimmed.length() - object.length(), object.length());
                        }
                    }
                    if (carryObject != null) {
                        deferredObject = carryObject;
                        labelStart = 0;
                    }
                }
                if (deferredObject == null && draft.metricKey.isEmpty()) {
                    MetricLabel carryMetric = metricSuffix(carryTrimmed);
                    if (carryMetric != null) {
                        mergeMetric(draft, carryMetric.key(), carryMetric.subject());
                        labelStart = 0;
                    }
                    else {
                        GenericMetric generic = genericMetricSuffix(carryTrimmed);
                        if (generic != null) {
                            mergeMetric(draft, generic.key(), generic.subject());
                            labelStart = 0;
                        }
                    }
                }
            }
        }

        String remaining = labelStart > 0 && labelStart <= left.length()
                ? left.substring(0, labelStart) : left;
        Direction directionInPrefix = findDirection(remaining);
        if (directionInPrefix != Direction.NONE) {
            mergeDirection(draft, directionInPrefix);
        }
        if (subjectFromMetric.isEmpty()) {
            String subject = NumericLexicons.subjectSuffix(stripDirectionAndStatistic(remaining));
            if (subject != null) {
                mergeSubject(draft, subject);
            }
        }
        else {
            mergeSubject(draft, subjectFromMetric);
        }

        // 右侧限定词、对象/指标、方向
        // 紧邻数字的“多”是近似表达（三千多 / 3000 多），不能被后面的对象标签吞掉
        if (right.startsWith("多")) {
            mergeQualifier(draft, Qualifier.APPROX);
            int before = right.length();
            right = trimStart(right.substring(1));
            suffixConsumed += before - right.length();
        }
        String rightQualifier = qualifierAliasAtPrefix(right);
        if (rightQualifier != null) {
            mergeQualifier(draft, NumericLexicons.QUALIFIERS.get(rightQualifier));
            int before = right.length();
            right = trimStart(right.substring(rightQualifier.length()));
            suffixConsumed += before - right.length();
        }
        while (right.startsWith("的")) {
            int before = right.length();
            right = trimStart(right.substring(1));
            suffixConsumed += before - right.length();
        }

        int labelLength = 0;
        MetricLabel suffixMetric = null;
        ObjectLabel suffixObject = null;
        if (draft.unitFamily == UnitFamily.COUNT) {
            suffixObject = objectPrefix(right);
            if (suffixObject == null) {
                suffixMetric = metricPrefix(right);
            }
        }
        else if (draft.unitFamily == UnitFamily.PERCENT) {
            suffixMetric = metricPrefix(right);
            if (suffixMetric == null) {
                suffixObject = objectPrefix(right);
            }
        }
        else {
            suffixMetric = metricPrefix(right);
        }
        if (suffixMetric == null && suffixObject == null) {
            GenericMetric generic = genericMetricPrefix(right);
            if (generic != null) {
                suffixMetric = new MetricLabel(generic.key(), generic.subject(), 0,
                        generic.length());
            }
        }
        if (suffixMetric != null) {
            mergeMetric(draft, suffixMetric.key(), suffixMetric.subject());
            labelLength = suffixMetric.length();
        }
        else if (suffixObject != null) {
            mergeObject(draft, suffixObject.object());
            labelLength = suffixObject.length();
            if (deferredObject != null && !deferredObject.object().equals(suffixObject.object())) {
                mergeSubject(draft, deferredObject.object());
            }
        }
        else if (draft.unitFamily == UnitFamily.COUNT || draft.unit.isEmpty()) {
            RawObject raw = rawObjectPrefix(right);
            if (raw != null) {
                mergeObject(draft, "raw:" + raw.object());
                labelLength = raw.length();
            }
        }
        if (deferredObject != null && draft.objectKey.isEmpty()) {
            mergeObject(draft, deferredObject.object());
        }
        if (labelLength > 0) {
            suffixConsumed += labelLength;
            String afterLabel = trimStart(right.substring(labelLength));
            String afterDirection = directionAliasAtPrefix(afterLabel);
            if (afterDirection != null) {
                mergeDirection(draft, NumericLexicons.DIRECTIONS.get(afterDirection));
                int before = afterLabel.length();
                afterLabel = trimStart(afterLabel.substring(afterDirection.length()));
                suffixConsumed += before - afterLabel.length();
            }
            String afterQualifier = qualifierAliasAtPrefix(afterLabel);
            if (afterQualifier != null) {
                mergeQualifier(draft, NumericLexicons.QUALIFIERS.get(afterQualifier));
                int before = afterLabel.length();
                afterLabel = trimStart(afterLabel.substring(afterQualifier.length()));
                suffixConsumed += before - afterLabel.length();
            }
        }
        else {
            String direction = directionAliasAtPrefix(right);
            if (direction != null) {
                mergeDirection(draft, NumericLexicons.DIRECTIONS.get(direction));
                int before = right.length();
                right = trimStart(right.substring(direction.length()));
                suffixConsumed += before - right.length();
            }
            String qualifier = qualifierAliasAtPrefix(right);
            if (qualifier != null) {
                mergeQualifier(draft, NumericLexicons.QUALIFIERS.get(qualifier));
                int before = right.length();
                right = trimStart(right.substring(qualifier.length()));
                suffixConsumed += before - right.length();
            }
            StatisticMatch suffixStatistic = NumericLexicons.statisticAtSuffix(right);
            if (suffixStatistic != null) {
                mergeStatistic(draft, suffixStatistic.key());
                suffixConsumed += suffixStatistic.alias().length();
            }
        }

        if (draft.unit.isEmpty() && !draft.objectKey.isEmpty()) {
            draft.unit = "个";
            draft.unitFamily = UnitFamily.COUNT;
            draft.kind = Kind.COUNT;
        }
        if (draft.unitFamily == UnitFamily.RATE
                && (draft.metricKey.isEmpty() || "THROUGHPUT".equals(draft.metricKey))) {
            draft.metricKey = draft.unit;
        }
        if (draft.unitFamily == UnitFamily.TIME && draft.kind != Kind.DURATION) {
            draft.kind = Kind.DURATION;
        }
        if (draft.unitFamily == UnitFamily.COUNT && draft.kind != Kind.COUNT) {
            draft.kind = Kind.COUNT;
        }
        if (suffixConsumed > 0) {
            draft.boundEnd = Math.min(text.length(), draft.end + suffixConsumed);
        }
    }

    private String carryLabel(String text, int clauseStart) {
        if (clauseStart <= 0) {
            return null;
        }
        char boundary = text.charAt(clauseStart - 1);
        if (boundary != ':' && boundary != '：' && boundary != '(' && boundary != '（') {
            return null;
        }
        int previousStart = clauseStart - 1;
        while (previousStart > 0 && !isHardBoundary(text, previousStart - 1)) {
            previousStart--;
        }
        return text.substring(previousStart, clauseStart - 1);
    }

    // ---------------------------------------------------------------- 标签匹配

    private ObjectLabel objectSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String object : NumericLexicons.OBJECTS) {
            for (String suffix : NumericLexicons.COUNT_SUFFIXES) {
                String phrase = object + suffix;
                if (text.endsWith(phrase)) {
                    return new ObjectLabel(object, text.length() - phrase.length(), phrase.length());
                }
            }
            if (text.endsWith(object)) {
                return new ObjectLabel(object, text.length() - object.length(), object.length());
            }
        }
        return null;
    }

    private ObjectLabel objectPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String stripped = NumericLexicons.stripLeadingObjectModifiers(text);
        int modifierLength = text.length() - stripped.length();
        for (String object : NumericLexicons.OBJECTS) {
            if (!stripped.startsWith(object)) {
                continue;
            }
            int length = object.length();
            for (String suffix : NumericLexicons.COUNT_SUFFIXES) {
                if (stripped.startsWith(suffix, object.length())) {
                    length += suffix.length();
                    break;
                }
            }
            // 对象词必须在词边界结束：“数据库”不能被截成“数据”，“模块化”不能截成“模块”
            if (!isLabelBoundary(stripped, length)) {
                continue;
            }
            return new ObjectLabel(object, 0, modifierLength + length);
        }
        return null;
    }

    /** 前缀标签结束处是否为可接受的词边界（结束/非汉字/连词/的/等） */
    private boolean isLabelBoundary(String text, int end) {
        if (end >= text.length()) {
            return true;
        }
        char ch = text.charAt(end);
        if (!NumericLexicons.isCjk(ch)) {
            return true;
        }
        if ("的等".indexOf(ch) >= 0) {
            return true;
        }
        for (String conjunction : NumericLexicons.CONJUNCTIONS) {
            if (text.startsWith(conjunction, end)) {
                return true;
            }
        }
        return false;
    }

    private RawObject rawObjectPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String stripped = NumericLexicons.stripLeadingObjectModifiers(text);
        int modifierLength = text.length() - stripped.length();
        int end = 0;
        while (end < stripped.length() && end < 4 && NumericLexicons.isCjk(stripped.charAt(end))) {
            if (isConjunctionAt(stripped, end) || "的了并".indexOf(stripped.charAt(end)) >= 0) {
                break;
            }
            end++;
        }
        if (end < 2) {
            return null;
        }
        String object = stripped.substring(0, end);
        if (NumericLexicons.OBJECT_STOP_WORDS.contains(object)
                || NumericLexicons.CONJUNCTIONS.contains(object)
                || NumericLexicons.OBJECT_STOP_WORDS.stream().anyMatch(object::startsWith)) {
            return null;
        }
        return new RawObject(object, modifierLength + end);
    }

    private boolean isConjunctionAt(String text, int index) {
        for (String conjunction : NumericLexicons.CONJUNCTIONS) {
            if (text.startsWith(conjunction, index)) {
                return true;
            }
        }
        return false;
    }

    private MetricLabel metricSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        MetricAlias alias = NumericLexicons.metricSuffix(text);
        if (alias == null) {
            return null;
        }
        return new MetricLabel(alias.rule().key(), alias.rule().subject(),
                text.length() - alias.alias().length(), alias.alias().length());
    }

    private MetricLabel metricPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        MetricAlias alias = NumericLexicons.metricPrefix(text);
        if (alias == null) {
            return null;
        }
        return new MetricLabel(alias.rule().key(), alias.rule().subject(), 0,
                alias.alias().length());
    }

    private GenericMetric genericMetricSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = NumericLexicons.GENERIC_METRIC_SUFFIX.matcher(text);
        if (!matcher.find() || matcher.end() != text.length()) {
            return null;
        }
        String phrase = matcher.group(1);
        return genericMetric(phrase, text.length() - phrase.length());
    }

    private GenericMetric genericMetricPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        Matcher matcher = GENERIC_METRIC_PREFIX.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String phrase = matcher.group(1);
        return genericMetric(phrase, 0);
    }

    private GenericMetric genericMetric(String phrase, int start) {
        for (String subject : NumericLexicons.SUBJECTS) {
            if (phrase.startsWith(subject) && phrase.length() - subject.length() >= 2) {
                String key = phrase.substring(subject.length());
                return new GenericMetric("raw:" + key, subject, start + subject.length(),
                        phrase.length() - subject.length());
            }
        }
        return new GenericMetric("raw:" + phrase, "", start, phrase.length());
    }

    private String directionAliasAtSuffix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String alias : NumericLexicons.DIRECTIONS.keySet()) {
            if (text.endsWith(alias)) {
                return alias;
            }
        }
        return null;
    }

    private Direction findDirection(String text) {
        if (text == null || text.isEmpty()) {
            return Direction.NONE;
        }
        Direction result = Direction.NONE;
        int bestIndex = -1;
        for (Map.Entry<String, Direction> entry : NumericLexicons.DIRECTIONS.entrySet()) {
            int index = text.lastIndexOf(entry.getKey());
            if (index >= 0 && index > bestIndex) {
                bestIndex = index;
                result = entry.getValue();
            }
        }
        return result;
    }

    private StatisticMatch findStatistic(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        StatisticMatch result = null;
        int bestIndex = -1;
        int bestLength = -1;
        for (Map.Entry<String, String> entry : NumericLexicons.STATISTICS.entrySet()) {
            int index = lower.lastIndexOf(entry.getKey().toLowerCase(Locale.ROOT));
            if (index >= 0 && (index > bestIndex
                    || (index == bestIndex && entry.getKey().length() > bestLength))) {
                bestIndex = index;
                bestLength = entry.getKey().length();
                result = new StatisticMatch(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    private String directionAliasAtPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String alias : NumericLexicons.DIRECTIONS.keySet()) {
            if (text.startsWith(alias)) {
                return alias;
            }
        }
        return null;
    }

    private String qualifierAliasAtPrefix(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        for (String alias : NumericLexicons.QUALIFIERS.keySet()) {
            if (text.startsWith(alias)) {
                return alias;
            }
        }
        return null;
    }

    private String stripDirectionAndStatistic(String text) {
        String value = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String alias : NumericLexicons.DIRECTIONS.keySet()) {
                if (value.endsWith(alias)) {
                    value = trimEnd(value.substring(0, value.length() - alias.length()));
                    changed = true;
                    break;
                }
            }
            if (changed) {
                continue;
            }
            for (String alias : NumericLexicons.STATISTICS.keySet()) {
                if (value.endsWith(alias)) {
                    value = trimEnd(value.substring(0, value.length() - alias.length()));
                    changed = true;
                    break;
                }
            }
        }
        return value;
    }

    // ---------------------------------------------------------------- 合并

    private void mergeMetric(Draft draft, String key, String subject) {
        if (key == null || key.isEmpty()) {
            return;
        }
        if (draft.metricKey.isEmpty()) {
            draft.metricKey = key;
        }
        else if (!draft.metricKey.equals(key)) {
            draft.ambiguousBinding = true;
        }
        if (!subject.isEmpty()) {
            mergeSubject(draft, subject);
        }
    }

    private void mergeObject(Draft draft, String object) {
        if (object == null || object.isEmpty()) {
            return;
        }
        if (draft.objectKey.isEmpty()) {
            draft.objectKey = object;
        }
        else if (!draft.objectKey.equals(object)) {
            draft.ambiguousBinding = true;
        }
    }

    private void mergeSubject(Draft draft, String subject) {
        if (subject == null || subject.isEmpty()) {
            return;
        }
        if (draft.subjectKey.isEmpty()) {
            draft.subjectKey = subject;
        }
        else if (!draft.subjectKey.equals(subject)) {
            draft.ambiguousBinding = true;
        }
    }

    private void mergeDirection(Draft draft, Direction direction) {
        if (direction == null || direction == Direction.NONE) {
            return;
        }
        if (draft.direction == Direction.NONE) {
            draft.direction = direction;
        }
        else if (draft.direction != direction) {
            draft.ambiguousBinding = true;
        }
    }

    private void mergeQualifier(Draft draft, Qualifier qualifier) {
        if (qualifier == null || qualifier == Qualifier.NONE) {
            return;
        }
        if (draft.qualifier == Qualifier.NONE) {
            draft.qualifier = qualifier;
        }
        else if (draft.qualifier != qualifier) {
            draft.ambiguousBinding = true;
        }
    }

    private void mergeStatistic(Draft draft, String statistic) {
        if (statistic == null || statistic.isEmpty()) {
            return;
        }
        if (draft.statisticKey.isEmpty()) {
            draft.statisticKey = statistic;
        }
        else if (!draft.statisticKey.equals(statistic)) {
            draft.ambiguousBinding = true;
        }
    }

    // ---------------------------------------------------------------- 行签名

    private void applyLineSignatures(String text, List<Draft> drafts) {
        Map<Integer, List<Draft>> byLine = new java.util.LinkedHashMap<>();
        for (Draft draft : drafts) {
            int lineStart = text.lastIndexOf('\n', Math.max(0, draft.start - 1)) + 1;
            byLine.computeIfAbsent(lineStart, k -> new ArrayList<>()).add(draft);
        }
        for (Map.Entry<Integer, List<Draft>> entry : byLine.entrySet()) {
            int lineStart = entry.getKey();
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) {
                lineEnd = text.length();
            }
            List<Draft> lineDrafts = entry.getValue();
            lineDrafts.sort(Comparator.comparingInt(d -> d.start));
            StringBuilder signature = new StringBuilder();
            int cursor = lineStart;
            for (Draft draft : lineDrafts) {
                if (draft.start < cursor) {
                    continue;
                }
                signature.append(canonicalFreeText(text.substring(cursor, draft.start)));
                signature.append('#').append(draft.kind.name()).append(':')
                        .append(draft.value).append(':').append(draft.unit).append('#');
                cursor = draft.end;
            }
            signature.append(canonicalFreeText(text.substring(cursor, lineEnd)));
            String value = signature.toString();
            for (Draft draft : lineDrafts) {
                draft.lineSignature = value;
            }
        }
        for (Draft draft : drafts) {
            if (draft.lineSignature == null) {
                draft.lineSignature = "";
            }
        }
    }

    // ---------------------------------------------------------------- 跨行/行内上下文

    /**
     * 为每个提及计算上下文签名：跨行标题 + 表格表头 + 行内项目标识/短标签。
     *
     * <p>只回答“这个数字属于哪个项目/表头/标签”，不改变数字本身的事实判定；
     * 无法识别的结构保持为空，由 Guard 的上下文闸门保守处理。
     */
    private void applyContextSignatures(String rawText, String prepared, List<Draft> drafts) {
        if (drafts.isEmpty()) {
            return;
        }
        String normalized = Normalizer.normalize(rawText == null ? "" : rawText,
                        Normalizer.Form.NFKC)
                .replace("\r\n", "\n").replace('\r', '\n');
        String[] rawLines = normalized.split("\n", -1);
        String[] preparedLines = prepared.split("\n", -1);

        Map<Integer, String> baseByLineStart = new java.util.LinkedHashMap<>();
        int lineStart = 0;
        String currentHeading = "";
        TableState table = null;
        for (int i = 0; i < preparedLines.length; i++) {
            String rawLine = i < rawLines.length ? rawLines[i] : "";
            String preparedLine = preparedLines[i];

            String heading = headingOf(rawLine);
            if (heading != null) {
                String canonical = canonicalContext(heading);
                currentHeading = canonical.isEmpty()
                        || NumericLexicons.GENERIC_CONTEXT_LABELS.contains(canonical)
                        ? "" : canonical;
            }

            boolean tableRow = isTableRow(preparedLine);
            if (tableRow) {
                if (table == null) {
                    table = new TableState();
                }
                if (table.header.isEmpty() && !isTableSeparator(preparedLine)) {
                    table.header = canonicalContext(preparedLine);
                }
            }
            else {
                table = null;
            }
            String tableContext = tableRow && table != null ? table.header : "";
            baseByLineStart.put(lineStart, joinContext(currentHeading, tableContext));

            lineStart += preparedLine.length() + 1;
        }

        for (Draft draft : drafts) {
            int draftLineStart = prepared.lastIndexOf('\n', Math.max(0, draft.start - 1)) + 1;
            String base = baseByLineStart.getOrDefault(draftLineStart, "");
            int lineEnd = prepared.indexOf('\n', draftLineStart);
            if (lineEnd < 0) {
                lineEnd = prepared.length();
            }
            String inline = inlineContext(prepared, draft.start, draftLineStart, lineEnd);
            draft.contextSignature = joinContext(base, inline);
        }
    }

    /** 同一行内、数字之前最近的项目标识或短冒号标签 */
    private String inlineContext(String text, int position, int lineStart, int lineEnd) {
        int limit = Math.min(position, lineEnd);
        int segmentStart = limit - 1;
        while (segmentStart >= lineStart
                && LABEL_SEPARATORS.indexOf(text.charAt(segmentStart)) < 0) {
            segmentStart--;
        }
        segmentStart++;

        String project = null;
        int projectEnd = -1;
        Matcher projectMatcher = PROJECT_TOKEN.matcher(text);
        projectMatcher.region(lineStart, limit);
        while (projectMatcher.find()) {
            project = projectMatcher.group();
            projectEnd = projectMatcher.end();
        }

        String colonLabel = null;
        int colon = limit > lineStart ? text.lastIndexOf(':', limit - 1) : -1;
        if (colon >= segmentStart) {
            int labelStart = segmentStart;
            int previousColon = colon > 0 ? text.lastIndexOf(':', colon - 1) : -1;
            if (previousColon >= segmentStart) {
                labelStart = previousColon + 1;
            }
            colonLabel = validContextLabel(text.substring(labelStart, colon).strip());
        }

        if (project != null && (colonLabel == null || projectEnd >= colon)) {
            return canonicalContext(project);
        }
        return colonLabel == null ? "" : colonLabel;
    }

    /** 短冒号标签是否可作为上下文：排除通用字段名与已知指标/对象标签 */
    private String validContextLabel(String candidate) {
        if (candidate == null) {
            return null;
        }
        String trimmed = candidate.strip();
        if (trimmed.length() < 2 || trimmed.length() > 20) {
            return null;
        }
        if (trimmed.codePoints().noneMatch(Character::isLetter)) {
            return null;
        }
        String canonical = canonicalContext(trimmed);
        if (canonical.isEmpty() || NumericLexicons.GENERIC_CONTEXT_LABELS.contains(canonical)) {
            return null;
        }
        if (NumericLexicons.metricSuffix(trimmed) != null
                || NumericLexicons.GENERIC_METRIC_SUFFIX.matcher(trimmed).find()) {
            return null;
        }
        if (isObjectCountLabel(trimmed)) {
            return null;
        }
        return canonical;
    }

    private boolean isObjectCountLabel(String text) {
        for (String object : NumericLexicons.OBJECTS) {
            if (text.equals(object)) {
                return true;
            }
            for (String suffix : NumericLexicons.COUNT_SUFFIXES) {
                if (text.equals(object + suffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String headingOf(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        Matcher matcher = HEADING_LINE.matcher(rawLine);
        if (matcher.matches()) {
            String content = matcher.group(1).strip();
            return content.isEmpty() ? null : content;
        }
        String trimmed = rawLine.strip();
        return PLAIN_TITLE.matcher(trimmed).matches() ? trimmed : null;
    }

    /** Markdown 表格行：至少两个非空单元格，避免把普通叙述中的单个竖线当表格 */
    private boolean isTableRow(String line) {
        if (line == null || line.indexOf('|') < 0) {
            return false;
        }
        String[] cells = line.trim().split("\\|", -1);
        int nonEmpty = 0;
        for (String cell : cells) {
            if (!cell.isBlank()) {
                nonEmpty++;
            }
        }
        return nonEmpty >= 2;
    }

    private boolean isTableSeparator(String line) {
        String[] cells = line.trim().split("\\|", -1);
        boolean sawCell = false;
        for (String cell : cells) {
            String value = cell.strip();
            if (value.isEmpty()) {
                continue;
            }
            if (!value.matches(":?-{2,}:?")) {
                return false;
            }
            sawCell = true;
        }
        return sawCell;
    }

    /** 上下文归一：日期片段先归一为 d，再去空白/标点，避免纯格式改写造成误报 */
    private String canonicalContext(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = CONTEXT_DATE.matcher(text).replaceAll("d").toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                result.appendCodePoint(cp);
            }
        });
        return result.toString();
    }

    private String joinContext(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second == null ? "" : second;
        }
        if (second == null || second.isEmpty() || second.equals(first)) {
            return first;
        }
        return first + "|" + second;
    }

    private String canonicalFreeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Direction> entry : NumericLexicons.DIRECTIONS.entrySet()) {
            value = value.replace(entry.getKey().toLowerCase(Locale.ROOT), directionMarker(entry.getValue()));
        }
        for (Map.Entry<String, Qualifier> entry : NumericLexicons.QUALIFIERS.entrySet()) {
            value = value.replace(entry.getKey().toLowerCase(Locale.ROOT), qualifierMarker(entry.getValue()));
        }
        for (Map.Entry<String, String> entry : NumericLexicons.STATISTICS.entrySet()) {
            value = value.replace(entry.getKey().toLowerCase(Locale.ROOT), "s" + entry.getValue());
        }
        StringBuilder result = new StringBuilder();
        value.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp)) {
                result.appendCodePoint(cp);
            }
        });
        return result.toString();
    }

    private String directionMarker(Direction direction) {
        return switch (direction) {
            case UP -> "dup";
            case DOWN -> "ddown";
            case NEUTRAL -> "dneutral";
            default -> "";
        };
    }

    private String qualifierMarker(Qualifier qualifier) {
        return switch (qualifier) {
            case APPROX -> "qapprox";
            case ABOVE -> "qabove";
            case BELOW -> "qbelow";
            case AT_LEAST -> "qatleast";
            case AT_MOST -> "qatmost";
            case TARGET -> "qtarget";
            default -> "";
        };
    }

    // ---------------------------------------------------------------- 文本辅助

    private static int clauseStart(String text, int position) {
        int index = position - 1;
        while (index >= 0) {
            if (isHardBoundary(text, index)) {
                return index + 1;
            }
            index--;
        }
        return 0;
    }

    private static int clauseEnd(String text, int position) {
        int index = position;
        while (index < text.length()) {
            if (isHardBoundary(text, index)) {
                return index;
            }
            index++;
        }
        return text.length();
    }

    static boolean isHardBoundary(String text, int index) {
        char ch = text.charAt(index);
        if (ch == '\n' || ch == '。' || ch == '！' || ch == '？' || ch == '；' || ch == ';'
                || ch == '、' || ch == '|' || ch == '：' || ch == ':'
                || ch == '(' || ch == ')' || ch == '（' || ch == '）'
                || ch == '【' || ch == '】' || ch == '[' || ch == ']'
                || ch == '《' || ch == '》' || ch == '{' || ch == '}') {
            return true;
        }
        if (ch == ',' || ch == '，') {
            return !(index > 0 && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index - 1))
                    && Character.isDigit(text.charAt(index + 1)));
        }
        if (ch == '.') {
            boolean digitOrLetterBefore = index > 0
                    && Character.isLetterOrDigit(text.charAt(index - 1));
            boolean digitOrLetterAfter = index + 1 < text.length()
                    && Character.isLetterOrDigit(text.charAt(index + 1));
            return !(digitOrLetterBefore && digitOrLetterAfter);
        }
        if (ch == '/') {
            return !(index > 0 && index + 1 < text.length()
                    && Character.isDigit(text.charAt(index - 1))
                    && Character.isDigit(text.charAt(index + 1)));
        }
        return false;
    }

    private static int skipSpaces(String text, int index) {
        int cursor = index;
        while (cursor < text.length() && (text.charAt(cursor) == ' ' || text.charAt(cursor) == '\t')) {
            cursor++;
        }
        return cursor;
    }

    private static boolean overlaps(boolean[] consumed, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(consumed.length, end); i++) {
            if (consumed[i]) {
                return true;
            }
        }
        return false;
    }

    private static void markConsumed(boolean[] consumed, int start, int end) {
        for (int i = Math.max(0, start); i < Math.min(consumed.length, end); i++) {
            consumed[i] = true;
        }
    }

    private static boolean isMagnitude(char ch) {
        return "万亿千百".indexOf(ch) >= 0;
    }

    private static BigDecimal magnitude(char ch) {
        return switch (ch) {
            case '万' -> BigDecimal.valueOf(10000);
            case '亿' -> BigDecimal.valueOf(100000000);
            case '千' -> BigDecimal.valueOf(1000);
            case '百' -> BigDecimal.valueOf(100);
            default -> BigDecimal.ONE;
        };
    }

    private static boolean isChineseNumeral(String token) {
        return token != null && !token.isEmpty() && !Character.isDigit(token.charAt(0));
    }

    private static boolean isAsciiLetter(char ch) {
        return (ch >= 'A' && ch <= 'Z') || (ch >= 'a' && ch <= 'z');
    }

    private static boolean isAsciiWordChar(char ch) {
        return isAsciiLetter(ch) || (ch >= '0' && ch <= '9')
                || ch == '+' || ch == '#' || ch == '.' || ch == '-';
    }

    private static UnitAlias unitAfter(String text, int end) {
        return NumericLexicons.unitAt(text, skipSpaces(text, end));
    }

    private static String canonical(BigDecimal value) {
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.scale() < 0) {
            normalized = normalized.setScale(0);
        }
        return normalized.toPlainString();
    }

    private static Kind kindFor(UnitFamily family) {
        return switch (family) {
            case PERCENT -> Kind.PERCENT;
            case MONEY -> Kind.MONEY;
            case SIZE -> Kind.SIZE;
            case RATE -> Kind.RATE;
            case TIME -> Kind.DURATION;
            case COUNT -> Kind.COUNT;
            default -> Kind.PLAIN;
        };
    }

    private static boolean isRangeSeparator(char ch) {
        return ch == '-' || ch == '~' || ch == '至' || ch == '到';
    }

    private static String openEndAt(String text, int index) {
        for (String marker : List.of("至今", "现在", "目前")) {
            if (text.startsWith(marker, index)) {
                return marker;
            }
        }
        return null;
    }

    private static String normalizeRaw(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    private static String trimEnd(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    private static String trimStart(String text) {
        int start = 0;
        while (start < text.length() && Character.isWhitespace(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private record DateValue(int start, int end, String value) {
    }

    private record VersionAnchor(String key, boolean meaningful) {
    }

    private record ObjectLabel(String object, int start, int length) {
    }

    private record MetricLabel(String key, String subject, int start, int length) {
    }

    private record GenericMetric(String key, String subject, int start, int length) {
    }

    private record RawObject(String object, int length) {
    }

    private static final class TableState {
        private String header = "";
    }

    private static final class Draft {
        private Kind kind = Kind.PLAIN;
        private String value = "";
        private String unit = "";
        private UnitFamily unitFamily = UnitFamily.PLAIN;
        private String metricKey = "";
        private String objectKey = "";
        private String subjectKey = "";
        private Direction direction = Direction.NONE;
        private Qualifier qualifier = Qualifier.NONE;
        private String statisticKey = "";
        private int start;
        private int end;
        private int boundEnd;
        private String lineSignature = "";
        private String contextSignature = "";
        private boolean ambiguousBinding;
        private String unsupportedRaw = "";
        private boolean supported = true;
    }
}
