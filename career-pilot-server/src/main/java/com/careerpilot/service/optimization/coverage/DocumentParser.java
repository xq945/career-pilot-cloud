package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerpilot.service.optimization.numeric.NumericMention;
import com.careerpilot.service.optimization.numeric.NumericMentionExtractor;

/**
 * 把简历/优化稿解析为章节化的块（修复 C）。
 *
 * <p>只识别 Markdown 标题作为章节；“项目：名称”等行解析为
 * {@link CoverageBlock.Type#PROJECT_LABEL}，保留项目身份但不改变章节归属，
 * 避免模型重排字段顺序时把整段内容错误地换章节。
 *
 * <p>表格行被转换为 {@code 表头: 值} 的键值事实文本，并保留列标签映射。
 * 表格缺少分隔行、表头不可用或数据行列数与表头不一致时，块被标记为
 * {@link CoverageBlock.ParseStatus#PARTIAL}：只允许规范化后完全相同的匹配，
 * 不允许“相似即等价”。
 *
 * <p>解析块数超过 {@link #MAX_BLOCKS} 时提前终止并返回
 * {@code complete=false}，由覆盖守卫按不可解析阻断，避免无界遍历。
 */
final class DocumentParser {

    /** 单份文档的最大块数；30k 字符简历远低于此值，超限说明结构异常。 */
    static final int MAX_BLOCKS = 2000;

    private static final Pattern HEADING = Pattern.compile("^\\s*(#{1,6})\\s+(.+)$");

    private static final Pattern BULLET = Pattern.compile("^(?:[-*+·•]|\\d+[.)])\\s+(.+)$");

    private static final Pattern CONTACT = Pattern.compile(
            "^(?:姓名|电话|手机|邮箱|联系方式|住址|地址|性别|年龄|生日"
                    + "|学历|学校|专业|毕业院校|专业方向)\\s*[:：]");

    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private static final Pattern PROJECT_LABEL_LINE = Pattern.compile("^项目\\s*[:：]");

    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{2,}:?");

    private static final Pattern PROJECT_LABEL = Pattern.compile(
            "项目\\s*[:：]\\s*([^\\s，,。；;|]{1,20})");

    private static final Pattern PROJECT_TOKEN = Pattern.compile(
            "项目\\s*([A-Za-z]{1,3}|[一二三四五六七八九十0-9]{1,3})(?![A-Za-z0-9])");

    private static final Map<String, String> GENERIC_HEADINGS = Map.ofEntries(
            Map.entry("项目经历", "项目经历"), Map.entry("项目经验", "项目经历"),
            Map.entry("项目", "项目经历"),
            Map.entry("工作经历", "工作经历"), Map.entry("工作经验", "工作经历"),
            Map.entry("实习经历", "工作经历"), Map.entry("实习", "工作经历"),
            Map.entry("教育经历", "教育经历"), Map.entry("教育背景", "教育经历"),
            Map.entry("教育", "教育经历"),
            Map.entry("专业技能", "技能"), Map.entry("技能清单", "技能"),
            Map.entry("技能", "技能"), Map.entry("技术栈", "技能"),
            Map.entry("基本信息", "基本信息"), Map.entry("个人信息", "基本信息"),
            Map.entry("联系方式", "基本信息"),
            Map.entry("荣誉证书", "荣誉"), Map.entry("荣誉奖项", "荣誉"),
            Map.entry("获奖情况", "荣誉"), Map.entry("荣誉", "荣誉"),
            Map.entry("个人总结", "个人总结"), Map.entry("个人优势", "个人优势"),
            Map.entry("自我评价", "自我评价"), Map.entry("求职意向", "求职意向"),
            Map.entry("核心工作", "工作内容"), Map.entry("工作内容", "工作内容"),
            Map.entry("主要职责", "工作内容"), Map.entry("职责", "工作内容"),
            Map.entry("证书", "证书"), Map.entry("认证", "证书"));

    private static final Set<String> GENERIC_LINE_WORDS = Set.of(
            "基本信息", "个人信息", "联系方式", "项目经历", "项目经验", "工作经历",
            "工作经验", "实习经历", "教育经历", "教育背景", "专业技能", "技能清单",
            "技术栈", "荣誉证书", "荣誉奖项", "获奖情况", "个人总结", "个人优势",
            "自我评价", "求职意向", "核心工作", "工作内容", "主要职责", "主修课程");

    private final NumericMentionExtractor extractor = new NumericMentionExtractor();

    private final FactTokenExtractor factTokenExtractor = new FactTokenExtractor();

    SectionedDocument parse(String raw) {
        String text = CoverageText.nfkc(raw).replace("\r\n", "\n").replace('\r', '\n');
        List<CoverageBlock> blocks = new ArrayList<>();
        List<Heading> headings = new ArrayList<>();
        String[] lines = text.split("\n", -1);
        int offset = 0;
        int i = 0;
        while (i < lines.length) {
            if (blocks.size() >= MAX_BLOCKS) {
                return new SectionedDocument(text, blocks, false);
            }
            String line = lines[i];
            int lineStart = offset;
            offset += line.length() + 1;
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                i++;
                continue;
            }

            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) {
                int level = heading.group(1).length();
                String title = heading.group(2).strip();
                while (!headings.isEmpty() && headings.get(headings.size() - 1).level >= level) {
                    headings.remove(headings.size() - 1);
                }
                String key = genericHeadingKey(title);
                headings.add(new Heading(level, key));
                String sectionKey = sectionKey(headings);
                String parentKey = parentKey(headings);
                boolean generic = isGenericHeading(title);
                blocks.add(block(CoverageBlock.Type.HEADING, line, title, sectionKey, parentKey,
                        lineStart, lineStart + line.length(), generic, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }

            if (isTableLine(trimmed)) {
                int end = i;
                while (end < lines.length && isTableLine(lines[end].strip())) {
                    end++;
                }
                // 只有存在 Markdown 分隔行时才按表格解析；否则 “学校 | 专业 | 学历”
                // 这类普通竖线文本会被误判为表格并产生 PARTIAL/UNPARSABLE。
                if (hasTableSeparator(lines, i, end)) {
                    addTable(lines, i, end, headingKeys(headings), headingParentKeys(headings),
                            blocks);
                    offset = lineStart;
                    for (int k = i; k < end; k++) {
                        offset += lines[k].length() + 1;
                    }
                    i = end;
                    continue;
                }
            }

            String sectionKey = headingKeys(headings);
            String parentKey = headingParentKeys(headings);
            Matcher bullet = BULLET.matcher(trimmed);
            if (bullet.matches()) {
                String content = bullet.group(1).strip();
                // 列表形式展示的联系方式同样不参与事实覆盖。
                boolean contact = content.isEmpty() || CONTACT.matcher(content).find()
                        || EMAIL.matcher(content).find();
                blocks.add(block(CoverageBlock.Type.LIST_ITEM, line, content, sectionKey, parentKey,
                        lineStart, lineStart + line.length(), contact, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }
            if (CONTACT.matcher(trimmed).find() || EMAIL.matcher(trimmed).find()) {
                blocks.add(block(CoverageBlock.Type.CONTACT, line, trimmed, sectionKey, parentKey,
                        lineStart, lineStart + line.length(), true, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }
            // 纯文本简历的章节名（无 Markdown 井号）：与优化稿的 ## 章节建立同一语义键。
            if (isBareSectionHeading(trimmed)) {
                while (!headings.isEmpty() && headings.get(headings.size() - 1).level >= 1) {
                    headings.remove(headings.size() - 1);
                }
                headings.add(new Heading(1, genericHeadingKey(trimmed)));
                blocks.add(block(CoverageBlock.Type.HEADING, line, trimmed,
                        sectionKey(headings), parentKey(headings),
                        lineStart, lineStart + line.length(), true, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }
            if (PROJECT_LABEL_LINE.matcher(trimmed).find()) {
                blocks.add(block(CoverageBlock.Type.PROJECT_LABEL, line, trimmed, sectionKey,
                        parentKey, lineStart, lineStart + line.length(), false, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }
            String canonicalLine = CoverageText.canonical(trimmed);
            if (canonicalLine.isEmpty() || GENERIC_LINE_WORDS.contains(canonicalLine)
                    || trimmed.matches("^[-=_*]{3,}$")) {
                blocks.add(block(CoverageBlock.Type.GENERIC, line, trimmed, sectionKey, parentKey,
                        lineStart, lineStart + line.length(), true, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                i++;
                continue;
            }
            blocks.add(block(CoverageBlock.Type.PARAGRAPH, line, trimmed, sectionKey, parentKey,
                    lineStart, lineStart + line.length(), false, Map.of(),
                    CoverageBlock.ParseStatus.PARSED));
            i++;
        }
        return new SectionedDocument(text, blocks);
    }

    // 表头/分隔行/数据行在同一个表格块中解析
    private void addTable(String[] lines, int start, int end, String sectionKey, String parentKey,
                          List<CoverageBlock> blocks) {
        int[] starts = new int[end - start];
        int cursor = 0;
        for (int k = 0; k < start; k++) {
            cursor += lines[k].length() + 1;
        }
        for (int k = start; k < end; k++) {
            starts[k - start] = cursor;
            cursor += lines[k].length() + 1;
        }

        List<String> header = List.of();
        int headerIndex = -1;
        int separatorIndex = -1;
        for (int k = start; k < end; k++) {
            List<String> cells = cells(lines[k].strip());
            if (isSeparator(cells)) {
                separatorIndex = k;
                break;
            }
            if (headerIndex < 0) {
                header = cells;
                headerIndex = k;
            }
        }
        boolean headerUsable = separatorIndex >= 0 && header.size() >= 2
                && header.stream().noneMatch(String::isBlank)
                && new HashSet<>(header).size() == header.size();

        for (int k = start; k < end; k++) {
            String line = lines[k];
            String trimmed = line.strip();
            List<String> cells = cells(trimmed);
            int rawStart = starts[k - start];
            int rawEnd = rawStart + line.length();
            if (isSeparator(cells)) {
                blocks.add(block(CoverageBlock.Type.TABLE_SEPARATOR, line, trimmed,
                        sectionKey, parentKey, rawStart, rawEnd, true, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                continue;
            }
            if (k == headerIndex) {
                blocks.add(block(CoverageBlock.Type.TABLE_HEADER, line, trimmed,
                        sectionKey, parentKey, rawStart, rawEnd, true, Map.of(),
                        CoverageBlock.ParseStatus.PARSED));
                continue;
            }
            boolean rowUsable = headerUsable && cells.size() == header.size();
            Map<String, String> columns = new LinkedHashMap<>();
            StringBuilder factText = new StringBuilder();
            for (int c = 0; c < cells.size(); c++) {
                String cell = cells.get(c).strip();
                if (cell.isEmpty()) {
                    continue;
                }
                String label = c < header.size() && !header.get(c).isBlank()
                        ? header.get(c).strip()
                        : "列" + (c + 1);
                columns.put(label, cell);
                factText.append(label).append(": ").append(cell).append('\n');
            }
            blocks.add(block(CoverageBlock.Type.TABLE_ROW, line, factText.toString(),
                    sectionKey, parentKey, rawStart, rawEnd, false, columns,
                    rowUsable ? CoverageBlock.ParseStatus.PARSED
                            : CoverageBlock.ParseStatus.PARTIAL));
        }
    }

    private CoverageBlock block(CoverageBlock.Type type, String rawLine, String content,
                                String sectionKey, String parentKey,
                                int rawStart, int rawEnd, boolean nonFact,
                                Map<String, String> columns,
                                CoverageBlock.ParseStatus parseStatus) {
        String factText = content == null ? "" : content;
        List<NumericMention> facts = type == CoverageBlock.Type.CONTACT
                || type == CoverageBlock.Type.GENERIC
                || type == CoverageBlock.Type.TABLE_HEADER
                || type == CoverageBlock.Type.TABLE_SEPARATOR
                ? List.of()
                : extractor.extract(factText);
        String projectKey = type == CoverageBlock.Type.HEADING ? "" : projectKeyOf(factText);
        String wordText = removeSpans(factText, facts);
        List<FactToken> tokens = nonFact ? List.of()
                : factTokenExtractor.extract(factText, facts, projectKey);
        return new CoverageBlock(type, rawLine, CoverageText.matchNormalize(rawLine),
                CoverageText.canonical(factText), factText, sectionKey, parentKey, projectKey,
                rawStart, rawEnd, nonFact, facts, CoverageText.contentWords(wordText),
                tokens, Map.copyOf(columns), parseStatus);
    }

    private static String projectKeyOf(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        Matcher label = PROJECT_LABEL.matcher(text);
        if (label.find()) {
            return CoverageText.canonical("项目" + label.group(1));
        }
        Matcher token = PROJECT_TOKEN.matcher(text);
        if (token.find()) {
            return CoverageText.canonical("项目" + token.group(1));
        }
        return "";
    }

    /** 内容词只比较非事实文本：日期/数字/版本本身由事实 token 比较。 */
    private static String removeSpans(String text, List<NumericMention> facts) {
        if (text == null || text.isEmpty() || facts.isEmpty()) {
            return text == null ? "" : text;
        }
        StringBuilder result = new StringBuilder(text);
        for (NumericMention fact : facts) {
            int start = Math.max(0, Math.min(fact.start(), result.length()));
            int end = Math.max(start, Math.min(fact.end(), result.length()));
            for (int i = start; i < end; i++) {
                result.setCharAt(i, ' ');
            }
        }
        return result.toString();
    }

    private static boolean isTableLine(String trimmed) {
        if (trimmed.indexOf('|') < 0) {
            return false;
        }
        // 列表项里的竖线是普通文本分隔，不是表格。
        if (BULLET.matcher(trimmed).matches()) {
            return false;
        }
        List<String> cells = cells(trimmed);
        int nonEmpty = 0;
        for (String cell : cells) {
            if (!cell.isBlank()) {
                nonEmpty++;
            }
        }
        return nonEmpty >= 2;
    }

    /** 连续竖线行中是否存在 Markdown 表格分隔行（{@code | --- | --- |}）。 */
    private static boolean hasTableSeparator(String[] lines, int start, int end) {
        for (int k = start; k < end; k++) {
            if (isSeparator(cells(lines[k].strip()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 纯文本章节名：整行规范化后恰好是已知章节词。
     * 带冒号的是项目内字段标签（如 “核心工作：”），不是章节。
     */
    private static boolean isBareSectionHeading(String trimmed) {
        if (trimmed.indexOf(':') >= 0 || trimmed.indexOf('：') >= 0) {
            return false;
        }
        String canonical = CoverageText.canonical(trimmed);
        return canonical.length() >= 2 && GENERIC_HEADINGS.containsKey(canonical);
    }

    private static List<String> cells(String line) {
        String[] parts = line.split("\\|", -1);
        int from = 0;
        int to = parts.length;
        if (from < to && parts[from].isBlank()) {
            from++;
        }
        if (to > from && parts[to - 1].isBlank()) {
            to--;
        }
        List<String> cells = new ArrayList<>();
        for (int i = from; i < to; i++) {
            cells.add(parts[i].strip());
        }
        return cells;
    }

    private static boolean isSeparator(List<String> cells) {
        boolean saw = false;
        for (String cell : cells) {
            if (cell.isBlank()) {
                continue;
            }
            if (!TABLE_SEPARATOR_CELL.matcher(cell).matches()) {
                return false;
            }
            saw = true;
        }
        return saw;
    }

    private static boolean isGenericHeading(String title) {
        return GENERIC_HEADINGS.containsKey(CoverageText.canonical(title));
    }

    private static String genericHeadingKey(String title) {
        String canonical = CoverageText.canonical(title);
        return GENERIC_HEADINGS.getOrDefault(canonical, canonical);
    }

    private static String headingKeys(List<Heading> headings) {
        StringBuilder result = new StringBuilder();
        for (Heading heading : headings) {
            if (result.length() > 0) {
                result.append('/');
            }
            result.append(heading.key);
        }
        return result.toString();
    }

    private static String headingParentKeys(List<Heading> headings) {
        if (headings.isEmpty()) {
            return "";
        }
        return headingKeys(headings.subList(0, headings.size() - 1));
    }

    private static String sectionKey(List<Heading> headings) {
        return headingKeys(headings);
    }

    private static String parentKey(List<Heading> headings) {
        return headingParentKeys(headings);
    }

    private record Heading(int level, String key) {
    }
}
