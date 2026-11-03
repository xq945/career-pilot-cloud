package com.careerpilot.service.optimization;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 简历原文归一化（定向优化链路专用，2026-10-21）。
 *
 * <p><b>为什么需要</b>：Tika 提取的 PDF 文本有两个会直接污染事实校验的特征：
 * <ol>
 *   <li>PDF 大纲（书签）标题被追加到正文末尾，形成一串 TAB 分隔的章节名
 *       （如 {@code \t基本信息 / \t实习经历 / …}）；</li>
 *   <li>PDF 按视觉行硬折行（如「简历定\n向优化」），一个段落被拆成多个块。</li>
 * </ol>
 * 事实校验按「块 + 章节」核对原文证据，重复章节与碎行会造成大量
 * 「原文同一章节未支持的数值/日期事实」「原文未支持的表述」误判。
 *
 * <p><b>边界</b>：只做「还原段落」所需的两件事，不做全角/标点归一（那是校验层的
 * 比较口径）。方法幂等；DOCX 等无书签、无硬折行的文本归一化后保持不变。
 * 入库文本（{@code resume.content}）不做任何修改，归一化只在优化链路的
 * Prompt 与事实校验之间共享同一份视图。
 */
public final class ResumeTextNormalizer {

    private ResumeTextNormalizer() {
    }

    /**
     * 已知章节名（与 {@code DocumentParser} 的 GENERIC_HEADINGS 键保持一致）。
     * 只在文末连续出现 ≥2 个时判定为 PDF 书签残留。
     */
    private static final Set<String> SECTION_NAMES = Set.of(
            "基本信息", "个人信息", "联系方式", "项目经历", "项目经验", "项目",
            "工作经历", "工作经验", "实习经历", "实习", "教育经历", "教育背景", "教育",
            "专业技能", "技能清单", "技能", "技术栈", "荣誉证书", "荣誉奖项", "获奖情况",
            "荣誉", "个人总结", "个人优势", "自我评价", "求职意向", "核心工作", "工作内容",
            "主要职责", "职责", "证书", "认证");

    /** Markdown 标题：独立成行，不参与硬折行合并 */
    private static final Pattern HEADING_LINE = Pattern.compile("^#{1,6}\\s+.*$");

    /**
     * 列表项：**允许**续行合并。PDF 的长列表项经常在视觉行末折断
     * （如「…导出及增」+「删等接口…」），若不合并，原文会被拆成多个块，
     * 与优化稿的完整条目无法对应，覆盖守卫会报 DELETED_FACT/UNCOVERED_DIFF。
     */
    private static final Pattern LIST_ITEM = Pattern.compile("^(?:[-*+·•]|\\d+[.)])\\s+.*$");

    /** 结构化字段行（姓名/电话/学校…）与邮箱行：各自独立成行，不合并 */
    private static final Pattern CONTACT = Pattern.compile(
            "^(?:姓名|电话|手机|邮箱|联系方式|住址|地址|性别|年龄|生日"
                    + "|学历|学校|专业|毕业院校|专业方向)\\s*[:：]");

    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /** 表格行：列结构有意义，不合并 */
    private static final Pattern TABLE = Pattern.compile(".*\\|.*");

    /** 段落结束符：以这些结尾的行不接续下一行 */
    private static final Pattern PARAGRAPH_END = Pattern.compile("[。！？!?…]$");

    /**
     * 归一化：统一换行 → 去文末书签残留 → 合并段内硬折行。
     *
     * @param raw 简历入库文本，可为 null
     * @return 归一化文本；null 输入原样返回
     */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        String text = raw.replace("\r\n", "\n").replace('\r', '\n');
        text = stripBookmarkTail(text);
        return mergeHardWraps(text);
    }

    // ---------------------------------------------------------------- 书签残留

    /**
     * 删除文末连续出现的纯章节名块（PDF 大纲残留）。
     *
     * <p>只处理文档末尾：正文中的同名章节标题会跟随内容，不会满足「其后只有空行」。
     * 连续少于 2 行时不动，避免把一份以空章节结尾的正常简历改坏。
     */
    private static String stripBookmarkTail(String text) {
        String[] lines = text.split("\n", -1);
        int end = lines.length;
        while (end > 0 && lines[end - 1].isBlank()) {
            end--;
        }
        int start = end;
        while (start > 0 && isSectionNameLine(lines[start - 1])) {
            start--;
        }
        if (end - start < 2) {
            return text;
        }
        List<String> kept = new ArrayList<>(lines.length);
        for (int i = 0; i < start; i++) {
            kept.add(lines[i]);
        }
        // 保留原文末尾的换行习惯：删除块之后把尾部空行补回一行
        for (int i = end; i < lines.length; i++) {
            kept.add(lines[i]);
        }
        return String.join("\n", kept);
    }

    private static boolean isSectionNameLine(String line) {
        String trimmed = line.strip();
        return !trimmed.isEmpty() && SECTION_NAMES.contains(trimmed);
    }

    // ---------------------------------------------------------------- 硬折行

    /**
     * 合并不足一行的硬折行：只在空行分隔的段落内部操作，不跨段落。
     *
     * <p>合并条件（全部满足）：
     * <ul>
     *   <li>两行都不是列表项 / Markdown 标题 / 表格行 / 结构化字段行；</li>
     *   <li>上一行不以句末标点（。！？…）结尾；</li>
     *   <li>上一行以中文、字母或数字结尾，下一行以中文、字母或数字开头；</li>
     *   <li>下一行不是单独的章节名。</li>
     * </ul>
     * 拉丁字母/数字相遇时补一个空格，其余直接拼接。
     */
    private static String mergeHardWraps(String text) {
        String[] lines = text.split("\n", -1);
        List<String> out = new ArrayList<>(lines.length);
        List<String> paragraph = new ArrayList<>();

        for (String line : lines) {
            if (line.isBlank()) {
                flushParagraph(paragraph, out);
                out.add(line);
            }
            else {
                paragraph.add(line);
            }
        }
        flushParagraph(paragraph, out);
        return String.join("\n", out);
    }

    private static void flushParagraph(List<String> paragraph, List<String> out) {
        if (paragraph.isEmpty()) {
            return;
        }
        StringBuilder buffer = null;
        // 未发生合并的行必须原样输出（DOCX 的 TAB 缩进也有意义）；
        // 只有真正合并过的段落才用 strip 后的拼接结果。
        String originalFirstLine = null;
        boolean merged = false;
        for (String line : paragraph) {
            String trimmed = line.strip();
            if (buffer == null) {
                buffer = new StringBuilder(trimmed);
                originalFirstLine = line;
                continue;
            }
            if (canMerge(buffer, trimmed)) {
                if (needsSpace(buffer.charAt(buffer.length() - 1), trimmed.charAt(0))) {
                    buffer.append(' ');
                }
                buffer.append(trimmed);
                merged = true;
            }
            else {
                out.add(merged ? buffer.toString() : originalFirstLine);
                buffer = new StringBuilder(trimmed);
                originalFirstLine = line;
                merged = false;
            }
        }
        if (buffer != null) {
            out.add(merged ? buffer.toString() : originalFirstLine);
        }
        paragraph.clear();
    }

    private static boolean canMerge(StringBuilder previous, String next) {
        if (next.isEmpty() || isStructural(next) || SECTION_NAMES.contains(next)) {
            return false;
        }
        String prevText = previous.toString();
        if (prevText.isEmpty() || PARAGRAPH_END.matcher(prevText).find()
                || SECTION_NAMES.contains(prevText.strip())) {
            return false;
        }
        // 列表项允许接续行（PDF 折行）；标题/表格/联系方式行不允许
        if (isStructural(prevText) && !LIST_ITEM.matcher(prevText.strip()).matches()) {
            return false;
        }
        char last = prevText.charAt(prevText.length() - 1);
        char first = next.charAt(0);
        return isContinuable(last) && isContinuable(first);
    }

    private static boolean isStructural(String line) {
        return HEADING_LINE.matcher(line).matches()
                || LIST_ITEM.matcher(line).matches()
                || CONTACT.matcher(line).find()
                || EMAIL.matcher(line).find()
                || TABLE.matcher(line).matches();
    }

    /**
     * 可承接下一行的字符：中文、英文、数字、连接符，以及顿号/逗号/分号/冒号这类
     * **句中**标点。PDF 折行经常落在这些标点之后（它们允许出现在行尾），
     * 只有句末标点（{@code 。！？…}）才表示段落结束，由 {@link #PARAGRAPH_END} 拦截。
     */
    private static boolean isContinuable(char ch) {
        return Character.isLetterOrDigit(ch)
                || "+/&%".indexOf(ch) >= 0
                || "、，,；;：:".indexOf(ch) >= 0;
    }

    /**
     * 仅在拉丁字母/数字/符号相邻时补空格；中文与中文直接拼接。
     * 例如 {@code ...Spring Security +} + {@code JWT 实现} → {@code ...Spring Security + JWT 实现}。
     */
    private static boolean needsSpace(char last, char first) {
        return isAsciiToken(last) || isAsciiToken(first);
    }

    private static boolean isAsciiToken(char ch) {
        return ch < 128 && !Character.isWhitespace(ch);
    }

}
