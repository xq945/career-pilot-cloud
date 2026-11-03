package com.careerpilot.service.optimization;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.coverage.DateEventRoleGuard;
import com.careerpilot.service.optimization.coverage.DocumentCoverageGuard;
import com.careerpilot.service.optimization.coverage.FactTokenExtractor;
import com.careerpilot.service.optimization.coverage.PromptTags;
import com.careerpilot.service.optimization.numeric.NumericFactGuard;
import com.careerpilot.service.optimization.numeric.TextRange;

/**
 * 简历定向优化的最小事实校验器（第三阶段，O-02）。
 *
 * <p>职责边界：只回答“这条模型输出能否作为**未经验证**的成功结果保存”，
 * 不做语义真实性证明，也不负责修复模型输出。它以**入库的简历原文**
 * （{@code resume.content}）为唯一事实来源，不接收 JD、Prompt、结构化分析等辅助材料——
 * 构造上杜绝把辅助材料当成原文。
 *
 * <p>{@link #checkWithIntegrity} 的实际检查顺序（任一硬性/风险项都会阻断保存）：
 * <ol>
 *   <li><b>结构完整性（修复 A）</b>：null 元素、超长证据、清洗丢失/截断；</li>
 *   <li><b>原文证据与正文一致性</b>：每条 {@code changes.before} 规范化后必须出现在简历原文中，
 *       每条 {@code changes.after} 规范化后必须出现在优化稿中；有改动却没有 changes 时拒绝；</li>
 *   <li><b>正文覆盖账本（修复 C）</b>：由 {@link DocumentCoverageGuard} 校验块级对应、change
 *       消费唯一性、章节/项目归属、删除事实、未声明的块内差异与不可解析区域；</li>
 *   <li><b>日期事件角色（修复 C）</b>：由 {@link DateEventRoleGuard} 校验日期角色、主体、
 *       章节与区间起止顺序；</li>
 *   <li><b>数字上下文（修复 B）</b>：由 {@link NumericFactGuard} 比较数值、单位、指标别名组、
 *       对象、主体、方向、限定词与统计口径；MISMATCH 与 INDETERMINATE 都阻断保存。
 *       覆盖守卫已证明事实等价的块区间会跳过数字比较（表格↔列表、行合并等格式转换）；</li>
 *   <li><b>新增事实风险</b>：专名/机构名、英文技术名词、职责升级、带分隔符的项目标题。
 *       风险项是**保守判定**，不宣称已证明模型捏造。</li>
 * </ol>
 *
 * <p>规范化只消除空白、换行、制表符、全半角与常见标点差异，以及行首 Markdown 结构符号；
 * 不删除汉字、字母、数字，避免“无关文本也能匹配”。校验消息不包含简历正文、联系方式或
 * 完整术语片段，只给类别、序号与变更项索引。
 */
@Component
public class ResumeOptimizationFactChecker {

    public enum Category {
        RESUME_CONTENT_MISSING,
        BEFORE_NOT_IN_RESUME,
        AFTER_NOT_IN_OPTIMIZED,
        CHANGES_EMPTY_BUT_CONTENT_CHANGED,
        CHANGES_INCOMPLETE,
        EVIDENCE_TOO_LONG,
        WEAK_EVIDENCE,
        AMBIGUOUS_EVIDENCE,
        NEW_NUMBER,
        NUMBER_CONTEXT_MISMATCH,
        NUMBER_CONTEXT_AMBIGUOUS,
        NUMBER_EXPRESSION_UNSUPPORTED,
        NEW_PROPER_NOUN,
        NEW_TECH_TERM,
        NEW_ACHIEVEMENT,
        NEW_HEADING_ENTITY,
        COVERAGE_NEW_FACT,
        COVERAGE_NEW_SECTION,
        COVERAGE_DELETED_FACT,
        COVERAGE_UNCOVERED_DIFF,
        COVERAGE_UNCOVERED_FACT,
        COVERAGE_SECTION_MISMATCH,
        COVERAGE_MOVE_UNSUPPORTED,
        COVERAGE_CHANGE_UNUSED,
        COVERAGE_CHANGE_DUPLICATE,
        COVERAGE_AMBIGUOUS,
        COVERAGE_UNPARSABLE,
        COVERAGE_NEW_CONTENT_WORDING,
        COVERAGE_FACT_CHANGED,
        DATE_EVENT_ROLE_MISMATCH,
        DATE_EVENT_CONTEXT_AMBIGUOUS,
        DATE_EVENT_SUBJECT_MISMATCH,
        DATE_EVENT_SECTION_MISMATCH,
        DATE_RANGE_ORDER_INVALID,
        INVALID_STRUCTURE
    }

    public enum Severity {
        /** 硬性证据/结构失败：不得保存 */
        BLOCKING,
        /** 可疑新增事实：规则无法证明捏造，但按保守策略同样不得直接保存 */
        RISK
    }

    /** 单条问题：message 已脱敏，可安全写入任务失败原因与日志 */
    public record Issue(Category category, Severity severity, String message, Integer changeIndex) {
    }

    /** 校验结果；passed=false 时调用方必须拒绝保存 */
    public record Result(boolean passed, List<Issue> issues) {

        public static Result pass() {
            return new Result(true, List.of());
        }

        public static Result fail(List<Issue> issues) {
            return new Result(false, List.copyOf(issues));
        }

        /** 面向任务的失败原因（≤480 字符，交给 stateService.fail 的 500 字符上限） */
        public String userMessage() {
            if (passed || issues.isEmpty()) {
                return "";
            }
            String joined = issues.stream()
                    .limit(MAX_MESSAGE_ISSUES)
                    .map(Issue::message)
                    .collect(Collectors.joining("；"));
            String suffix = issues.size() > MAX_MESSAGE_ISSUES
                    ? "；等 " + issues.size() + " 项问题"
                    : "";
            String message = "优化结果未通过事实校验，未保存：" + joined + suffix;
            return message.length() > MAX_MESSAGE_LENGTH
                    ? message.substring(0, MAX_MESSAGE_LENGTH)
                    : message;
        }

        /** 日志口径与用户文案一致（不含正文） */
        public String logSummary() {
            return userMessage();
        }
    }

    private static final int MAX_MESSAGE_ISSUES = 5;

    private static final int MAX_MESSAGE_LENGTH = 480;

    /** 同类新增事实最多报告条数（超出只给汇总，避免消息淹没关键问题） */
    private static final int MAX_ITEMS_PER_CATEGORY = 5;

    /** 行首 Markdown 结构前缀（标题 / 无序列表 / 有序列表），只用于消除格式差异 */
    private static final Pattern LINE_PREFIX = Pattern.compile(
            "(?m)^\\s*(?:#{1,6}\\s*|[-*+·•]\\s+|\\d+[.)]\\s+)+");

    private final NumericFactGuard numericFactGuard = new NumericFactGuard();

    private final DocumentCoverageGuard coverageGuard = new DocumentCoverageGuard();

    private final DateEventRoleGuard dateEventRoleGuard = new DateEventRoleGuard();

    /** 「项目名 / 公司名 | 时间」形式的标题（只检查带分隔符的条目，避免误伤普通章节标题） */
    private static final Pattern HEADING_ENTITY = Pattern.compile(
            "(?m)^#{2,4}\\s*([^|｜\\n]{2,40})\\s*[|｜]");

    /** 结构性章节名，不作为“新项目/新机构”判断 */
    private static final Set<String> GENERIC_HEADINGS = Set.of(
            "项目经历", "项目经验", "实习经历", "工作经历", "工作经验", "教育经历", "教育背景",
            "专业技能", "技能清单", "个人总结", "个人优势", "自我评价", "基本信息", "联系方式",
            "荣誉证书", "荣誉奖项", "获奖情况", "技术栈", "核心工作", "工作内容", "主要职责");

    /** 单个短 ASCII 技术词（AI / Go / C# / C++ 等）；用于短证据豁免 */
    private static final Pattern SHORT_ASCII_TOKEN = Pattern.compile("[A-Za-z][A-Za-z0-9+#.]{1,15}");

    /** 单字母编程语言（C / R）；只在原文中作为独立词时豁免 */
    private static final Pattern SINGLE_LETTER_TECH = Pattern.compile("[CcRr]");

    /** 版本号形式（1.0 / 21.0 等）；用于短证据豁免 */
    private static final Pattern VERSION_TOKEN = Pattern.compile("\\d+(?:\\.\\d+)+");

    /** 低于此有效字符数（字母/数字/汉字）的 before 需要额外豁免 */
    private static final int MIN_EVIDENCE_TEXT_CHARS = 4;

    /** 低于此有效字符数的 before 若重复出现，视为无法唯一定位 */
    private static final int AMBIGUITY_TEXT_CHARS = 8;

    /**
     * 兼容入口：传入列表视为完整证据（不经过清洗截断）。
     * 新调用方应使用 {@link #checkWithIntegrity}，否则无法感知清洗信息损失。
     */
    public Result check(String resumeContent, String optimizedContent, List<ChangeItem> changes) {
        return checkWithIntegrity(resumeContent, optimizedContent, SanitizedChanges.of(changes));
    }

    /**
     * 校验一份模型输出（带清洗信息）。
     *
     * @param resumeContent    入库的简历原文（唯一事实来源；null/空白即失败）
     * @param optimizedContent 清洗后的优化稿全文
     * @param changes          清洗结果（保留项 + 原始条数/丢弃/截断元数据）
     */
    public Result checkWithIntegrity(String resumeContent, String optimizedContent,
                                     SanitizedChanges changes) {
        List<Issue> issues = new ArrayList<>();

        String resume = normalize(resumeContent);
        if (resume.isEmpty()) {
            issues.add(new Issue(Category.RESUME_CONTENT_MISSING, Severity.BLOCKING,
                    "简历原文为空，无法进行事实校验", null));
            return Result.fail(issues);
        }

        String optimized = normalize(optimizedContent);
        if (optimized.isEmpty()) {
            issues.add(new Issue(Category.INVALID_STRUCTURE, Severity.BLOCKING,
                    "优化稿为空，无法进行事实校验", null));
            return Result.fail(issues);
        }

        List<ChangeItem> items = changes == null ? List.of() : changes.changes();
        boolean contentChanged = !resume.equals(optimized);

        // null 元素是结构错误：无论正文是否变化都不接受
        if (changes != null && changes.nullCount() > 0) {
            issues.add(new Issue(Category.INVALID_STRUCTURE, Severity.BLOCKING,
                    "changes 包含 null 元素，输出结构不合格", null));
        }
        // before/after 超长：不允许“截断后的前缀”作为完整证据
        if (changes != null && changes.evidenceTooLong()) {
            issues.add(new Issue(Category.EVIDENCE_TOO_LONG, Severity.BLOCKING,
                    "变更证据超过允许长度（" + SanitizedChanges.EVIDENCE_TEXT_LIMIT
                            + " 字符），未保存为有效优化结果", null));
        }
        // 丢弃/截断/超上限且正文发生变化：剩余 changes 无法证明正文改动已完整核对
        if (changes != null && changes.hasInformationLoss() && contentChanged) {
            issues.add(new Issue(Category.CHANGES_INCOMPLETE, Severity.BLOCKING,
                    changes.summary() + "，无法证明正文改动已完整核对", null));
        }

        checkChanges(resumeContent, resume, optimized, items, issues);
        DocumentCoverageGuard.Audit coverage = coverageGuard.check(
                resumeContent, optimizedContent, items);
        addCoverageIssues(coverage, issues);
        DateEventRoleGuard.Audit dateRoles = dateEventRoleGuard.check(
                resumeContent, optimizedContent, coverage.sectionMap());
        addDateRoleIssues(dateRoles, issues);
        checkNumericFacts(resumeContent, optimizedContent, items,
                coverage.ignoredResume(), coverage.ignoredOptimized(), issues);
        checkNewProperNouns(resumeContent, optimizedContent, issues);
        checkNewTechTerms(resumeContent, optimizedContent, issues);
        checkNewAchievements(resume, optimized, issues);
        checkNewHeadingEntities(resume, optimizedContent, issues);

        return issues.isEmpty() ? Result.pass() : Result.fail(issues);
    }

    // ---------------------------------------------------------------- 原文证据与正文一致性

    private static void checkChanges(String rawResume, String resume, String optimized,
                                     List<ChangeItem> changes, List<Issue> issues) {
        if (changes == null || changes.isEmpty()) {
            // 没有 changes 时，正文只能与原文等价（仅格式差异）；否则无法核对改动来源
            if (!resume.equals(optimized)) {
                issues.add(new Issue(Category.CHANGES_EMPTY_BUT_CONTENT_CHANGED, Severity.BLOCKING,
                        "优化稿有改动但未提供逐项修改说明", null));
            }
            return;
        }

        for (int i = 0; i < changes.size(); i++) {
            ChangeItem item = changes.get(i);
            if (item == null) {
                issues.add(new Issue(Category.INVALID_STRUCTURE, Severity.BLOCKING,
                        "changes[" + i + "] 为空", i));
                continue;
            }

            String rawBefore = PromptTags.strip(item.getBefore());
            String before = normalize(rawBefore);
            if (before.isEmpty()) {
                issues.add(new Issue(Category.BEFORE_NOT_IN_RESUME, Severity.BLOCKING,
                        "changes[" + i + "] 的 before 为空，无法提供原文证据", i));
            }
            else if (!resume.contains(before)) {
                issues.add(new Issue(Category.BEFORE_NOT_IN_RESUME, Severity.BLOCKING,
                        "changes[" + i + "] 的 before 未能在简历原文中找到", i));
            }
            else {
                checkEvidenceStrength(rawBefore, rawResume, resume, before, i, issues);
            }

            // 当前业务语义：sanitizeChanges 丢弃缺 after 的元素，Prompt 规则 6 要求
            // after 必须能在 optimizedContent 中找到；因此所有保留的 changes（含 TRIM）
            // 都按“after 可定位”校验。若未来允许“无 after 的删除”，需在清洗与校验
            // 同时扩展，而不是在这里直接跳过。
            String after = normalize(PromptTags.strip(item.getAfter()));
            if (after.isEmpty()) {
                issues.add(new Issue(Category.AFTER_NOT_IN_OPTIMIZED, Severity.BLOCKING,
                        "changes[" + i + "] 的 after 为空，无法与优化稿核对", i));
            }
            else if (!optimized.contains(after)) {
                issues.add(new Issue(Category.AFTER_NOT_IN_OPTIMIZED, Severity.BLOCKING,
                        "changes[" + i + "] 的 after 未能在优化稿中找到", i));
            }
        }
    }

    /**
     * 最低证据强度（Task C）：
     * <ul>
     *   <li>不能只有标点/结构符号；</li>
     *   <li>有效字符（汉字/字母/数字）至少 4 个，除非是原文中独立出现的短 ASCII 技术词
     *       （AI/Go/C#）或版本号；</li>
     *   <li>短 ASCII 词必须是原文中的独立词，不能是更长单词的前缀；</li>
     *   <li>有效字符少于 8 且原文出现多次 → 无法唯一定位（RISK，阻断）。</li>
     * </ul>
     * 这是可测试的下限，不是完整的语义/位置校验；章节绑定仍属于后续任务。
     */
    private static void checkEvidenceStrength(String rawBefore, String rawResume,
                                              String normalizedResume, String normalizedBefore,
                                              int index, List<Issue> issues) {
        int textChars = textCharCount(normalizedBefore);
        boolean recognizedShort = isRecognizedShortEvidence(rawBefore, rawResume);

        if (textChars == 0) {
            issues.add(new Issue(Category.WEAK_EVIDENCE, Severity.BLOCKING,
                    "changes[" + index + "] 的 before 仅含标点/结构符号，不能作为原文证据", index));
            return;
        }
        if (textChars < MIN_EVIDENCE_TEXT_CHARS && !recognizedShort) {
            issues.add(new Issue(Category.WEAK_EVIDENCE, Severity.BLOCKING,
                    "changes[" + index + "] 的 before 过短，不能作为独立原文证据", index));
            return;
        }
        if (isSingleAsciiToken(rawBefore) && !hasAsciiBoundary(rawBefore.strip(), rawResume)) {
            issues.add(new Issue(Category.WEAK_EVIDENCE, Severity.BLOCKING,
                    "changes[" + index + "] 的 before 不是原文中的独立词，证据强度不足", index));
            return;
        }
        if (textChars < AMBIGUITY_TEXT_CHARS
                && countOccurrences(normalizedResume, normalizedBefore) > 1) {
            issues.add(new Issue(Category.AMBIGUOUS_EVIDENCE, Severity.RISK,
                    "changes[" + index + "] 的 before 在原文中出现多次，无法唯一定位", index));
        }
    }

    private static int textCharCount(String text) {
        return (int) text.codePoints().filter(Character::isLetterOrDigit).count();
    }

    private static boolean isRecognizedShortEvidence(String rawBefore, String rawResume) {
        if (rawBefore == null) {
            return false;
        }
        String value = rawBefore.strip();
        if (VERSION_TOKEN.matcher(value).matches()) {
            return rawResume != null && rawResume.contains(value);
        }
        if (SINGLE_LETTER_TECH.matcher(value).matches()) {
            return hasAsciiBoundary(value, rawResume);
        }
        if (!SHORT_ASCII_TOKEN.matcher(value).matches()) {
            return false;
        }
        return hasAsciiBoundary(value, rawResume);
    }

    private static boolean isSingleAsciiToken(String rawBefore) {
        if (rawBefore == null) {
            return false;
        }
        String value = rawBefore.strip();
        return SHORT_ASCII_TOKEN.matcher(value).matches()
                || SINGLE_LETTER_TECH.matcher(value).matches();
    }

    /** 独立词边界：避免 Java 匹配 JavaScript、AB 匹配 A B（去空白后的拼接） */
    private static boolean hasAsciiBoundary(String value, String rawResume) {
        if (rawResume == null || value == null || value.isEmpty()) {
            return false;
        }
        return Pattern.compile("(?<![A-Za-z0-9+#])" + Pattern.quote(value) + "(?![A-Za-z0-9+#])")
                .matcher(rawResume).find();
    }

    /** 只判断是否出现多次（最多返回 2），用于短证据唯一性 */
    private static int countOccurrences(String haystack, String needle) {
        if (haystack == null || needle == null || needle.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            if (count > 1) {
                return count;
            }
            index += Math.max(1, needle.length());
        }
        return count;
    }

    // ---------------------------------------------------------------- 新增事实风险

    /**
     * 数字上下文校验（修复 B）：由 {@link NumericFactGuard} 返回三态结果。
     * MISMATCH 与 INDETERMINATE 都按 RISK 阻断保存；没有“值+单位相同即通过”的路径。
     */
    private void checkNumericFacts(String resumeContent, String optimizedContent,
                                   List<ChangeItem> items,
                                   List<TextRange> ignoredResume,
                                   List<TextRange> ignoredOptimized,
                                   List<Issue> issues) {
        NumericFactGuard.Audit audit = numericFactGuard.check(
                resumeContent, optimizedContent, items, ignoredResume, ignoredOptimized);
        Map<Category, Integer> counts = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (NumericFactGuard.Finding finding : audit.findings()) {
            if (finding.verdict() == NumericFactGuard.Verdict.MATCH) {
                continue;
            }
            Category category = numericCategory(finding);
            String key = category + "|" + finding.changeIndex() + "|" + finding.occurrence()
                    + "|" + finding.reason();
            if (!seen.add(key)) {
                continue;
            }
            int count = counts.merge(category, 1, Integer::sum);
            if (count <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        numericMessage(category, finding, count), finding.changeIndex()));
            }
        }
        counts.forEach((category, count) -> {
            if (count > MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        "优化稿另有 " + (count - MAX_ITEMS_PER_CATEGORY)
                                + " 处数字一致性问题（保守判定为未通过）", null));
            }
        });
    }

    private static void addCoverageIssues(DocumentCoverageGuard.Audit audit, List<Issue> issues) {
        Map<Category, Integer> counts = new LinkedHashMap<>();
        for (DocumentCoverageGuard.Finding finding : audit.findings()) {
            Category category = coverageCategory(finding.code());
            int count = counts.merge(category, 1, Integer::sum);
            if (count <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        coverageMessage(category, finding), finding.changeIndex()));
            }
        }
        counts.forEach((category, count) -> {
            if (count > MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        "优化稿另有 " + (count - MAX_ITEMS_PER_CATEGORY)
                                + " 处正文覆盖问题（保守判定为未通过）", null));
            }
        });
    }

    private static Category coverageCategory(DocumentCoverageGuard.Code code) {
        return switch (code) {
            case NEW_FACT -> Category.COVERAGE_NEW_FACT;
            case NEW_SECTION -> Category.COVERAGE_NEW_SECTION;
            case DELETED_FACT -> Category.COVERAGE_DELETED_FACT;
            case UNCOVERED_DIFF -> Category.COVERAGE_UNCOVERED_DIFF;
            case UNCOVERED_FACT -> Category.COVERAGE_UNCOVERED_FACT;
            case SECTION_MISMATCH -> Category.COVERAGE_SECTION_MISMATCH;
            case MOVE_UNSUPPORTED -> Category.COVERAGE_MOVE_UNSUPPORTED;
            case CHANGE_UNUSED -> Category.COVERAGE_CHANGE_UNUSED;
            case CHANGE_DUPLICATE -> Category.COVERAGE_CHANGE_DUPLICATE;
            case AMBIGUOUS -> Category.COVERAGE_AMBIGUOUS;
            case UNPARSABLE -> Category.COVERAGE_UNPARSABLE;
            case NEW_CONTENT_WORDING -> Category.COVERAGE_NEW_CONTENT_WORDING;
            case FACT_CHANGED -> Category.COVERAGE_FACT_CHANGED;
        };
    }

    private static String coverageMessage(Category category, DocumentCoverageGuard.Finding finding) {
        int block = finding.blockIndex() + 1;
        Integer change = finding.changeIndex();
        String changePrefix = change == null ? "" : "changes[" + change + "] ";
        return switch (category) {
            case COVERAGE_NEW_FACT -> "优化稿第 " + block + " 块出现原文同一章节未支持的数值/日期事实";
            case COVERAGE_NEW_SECTION -> "优化稿第 " + block + " 块出现原文没有的章节/项目标题";
            case COVERAGE_DELETED_FACT -> "原文第 " + block + " 块的事实未在优化稿中保留";
            case COVERAGE_UNCOVERED_DIFF -> "优化稿第 " + block + " 块存在未被 changes 覆盖的正文差异";
            case COVERAGE_UNCOVERED_FACT -> changePrefix + "未覆盖同一位置的全部事实差异";
            case COVERAGE_SECTION_MISMATCH -> changePrefix + "的章节与正文实际章节不一致";
            case COVERAGE_MOVE_UNSUPPORTED -> changePrefix + "涉及跨章节移动事实（当前版本不支持）";
            case COVERAGE_CHANGE_UNUSED -> changePrefix + "未被任何正文差异消费（重复或无关证据）";
            case COVERAGE_CHANGE_DUPLICATE -> changePrefix + "与另一条变更重复，不能作为覆盖证据";
            case COVERAGE_AMBIGUOUS -> change == null
                    ? "优化稿第 " + block + " 块无法唯一定位"
                    : changePrefix + "无法唯一定位";
            case COVERAGE_UNPARSABLE -> "优化稿第 " + block + " 块无法解析且发生变化";
            case COVERAGE_NEW_CONTENT_WORDING -> "优化稿第 " + block + " 块出现原文未支持的表述";
            case COVERAGE_FACT_CHANGED -> changePrefix + "的 before/after 事实不一致";
            default -> "正文覆盖校验未通过";
        };
    }

    private static void addDateRoleIssues(DateEventRoleGuard.Audit audit, List<Issue> issues) {
        Map<Category, Integer> counts = new LinkedHashMap<>();
        for (DateEventRoleGuard.Finding finding : audit.findings()) {
            Category category = dateRoleCategory(finding.code());
            int count = counts.merge(category, 1, Integer::sum);
            if (count <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        "优化稿第 " + (finding.targetBlock() + 1) + " 块"
                                + dateRoleMessage(category), null));
            }
        }
        counts.forEach((category, count) -> {
            if (count > MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(category, Severity.RISK,
                        "优化稿另有 " + (count - MAX_ITEMS_PER_CATEGORY)
                                + " 处日期事件角色问题（保守判定为未通过）", null));
            }
        });
    }

    private static Category dateRoleCategory(DateEventRoleGuard.Code code) {
        return switch (code) {
            case ROLE_MISMATCH -> Category.DATE_EVENT_ROLE_MISMATCH;
            case CONTEXT_AMBIGUOUS -> Category.DATE_EVENT_CONTEXT_AMBIGUOUS;
            case SUBJECT_MISMATCH -> Category.DATE_EVENT_SUBJECT_MISMATCH;
            case SECTION_MISMATCH -> Category.DATE_EVENT_SECTION_MISMATCH;
            case RANGE_ORDER_INVALID -> Category.DATE_RANGE_ORDER_INVALID;
        };
    }

    private static String dateRoleMessage(Category category) {
        return switch (category) {
            case DATE_EVENT_ROLE_MISMATCH -> "的日期事件角色与原文不一致";
            case DATE_EVENT_CONTEXT_AMBIGUOUS -> "的日期事件上下文无法与原文可靠对应";
            case DATE_EVENT_SUBJECT_MISMATCH -> "的日期对应事件主体与原文不一致";
            case DATE_EVENT_SECTION_MISMATCH -> "的日期事件发生跨章节移动";
            case DATE_RANGE_ORDER_INVALID -> "的日期区间顺序无效（起点晚于终点）";
            default -> "的日期事件校验未通过";
        };
    }

    private static Category numericCategory(NumericFactGuard.Finding finding) {
        if (finding.verdict() == NumericFactGuard.Verdict.INDETERMINATE) {
            return Category.NUMBER_CONTEXT_AMBIGUOUS;
        }
        return switch (finding.reason()) {
            case MISMATCH_NEW_VALUE -> Category.NEW_NUMBER;
            case MISMATCH_UNSUPPORTED_CHANGED -> Category.NUMBER_EXPRESSION_UNSUPPORTED;
            default -> Category.NUMBER_CONTEXT_MISMATCH;
        };
    }

    private static String numericMessage(Category category, NumericFactGuard.Finding finding,
                                         int count) {
        String prefix = finding.changeIndex() == null
                ? "优化稿第 " + count + " 处"
                : "changes[" + finding.changeIndex() + "] 的 after 中第 " + count + " 处";
        return switch (category) {
            case NEW_NUMBER -> prefix + "出现原文未支持的数字（可疑新增事实，需人工确认）";
            case NUMBER_EXPRESSION_UNSUPPORTED -> prefix
                    + "数值表达暂不支持校验（保守判定为未通过）";
            case NUMBER_CONTEXT_AMBIGUOUS -> prefix
                    + "数值无法与原文建立可靠对应（上下文不明确，保守判定为未通过）";
            default -> prefix
                    + "数值与原文的单位、指标、对象或上下文不一致（可疑数字篡改，需人工确认）";
        };
    }

    private static void checkNewProperNouns(String resumeContent, String optimizedContent,
                                            List<Issue> issues) {
        String resume = normalize(resumeContent);
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = FactTokenExtractor.PROPER_NOUN.matcher(
                nfkc(stripLinePrefixes(nullToEmpty(optimizedContent))));
        int added = 0;
        while (matcher.find()) {
            String key = normalize(FactTokenExtractor.properNounCore(matcher.group(1)));
            if (key.length() < 2 || !seen.add(key)
                    || FactTokenExtractor.properNounSupported(resume, key)) {
                continue;
            }
            added++;
            if (added <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(Category.NEW_PROPER_NOUN, Severity.RISK,
                        "优化稿出现第 " + added + " 处原文未支持的专名/机构名（可疑新增事实，需人工确认）", null));
            }
        }
        addOverflowIssue(Category.NEW_PROPER_NOUN, added, "专名/机构名", issues);
    }

    private static void checkNewTechTerms(String resumeContent, String optimizedContent,
                                          List<Issue> issues) {
        Set<String> supported = englishTerms(resumeContent);
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = FactTokenExtractor.ENGLISH_WORD.matcher(
                nfkc(stripLinePrefixes(nullToEmpty(optimizedContent))));
        int added = 0;
        while (matcher.find()) {
            String token = matcher.group().toLowerCase(Locale.ROOT);
            if (token.length() < 2 || FactTokenExtractor.ENGLISH_STOP_WORDS.contains(token)
                    || !seen.add(token) || supported.contains(token)) {
                continue;
            }
            added++;
            if (added <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(Category.NEW_TECH_TERM, Severity.RISK,
                        "优化稿出现第 " + added + " 处原文未支持的技术名词（可疑新增事实，需人工确认）", null));
            }
        }
        addOverflowIssue(Category.NEW_TECH_TERM, added, "技术名词", issues);
    }

    /**
     * 职责/成果升级：优化稿出现、原文全文没有的“主导/负责/独立完成”等表述。
     * 检查整篇正文而不是只看 changes，避免未记录片段绕过。
     */
    private static void checkNewAchievements(String resume, String optimized, List<Issue> issues) {
        int added = 0;
        for (String phrase : FactTokenExtractor.OWNERSHIP_ESCALATIONS) {
            String key = normalize(phrase);
            if (key.isEmpty() || !optimized.contains(key) || resume.contains(key)) {
                continue;
            }
            added++;
            if (added <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(Category.NEW_ACHIEVEMENT, Severity.RISK,
                        "优化稿出现第 " + added + " 处原文未支持的职责/成果表述（可疑新增事实，需人工确认）", null));
            }
        }
        addOverflowIssue(Category.NEW_ACHIEVEMENT, added, "职责/成果表述", issues);
    }

    /** 新增项目/机构标题：只检查「### 名称 | 时间」这类带分隔符的条目 */
    private static void checkNewHeadingEntities(String resume, String optimizedContent,
                                                List<Issue> issues) {
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = HEADING_ENTITY.matcher(nfkc(optimizedContent));
        int added = 0;
        while (matcher.find()) {
            String key = normalize(matcher.group(1));
            if (key.length() < 2 || GENERIC_HEADINGS.contains(key)
                    || !seen.add(key) || resume.contains(key)) {
                continue;
            }
            added++;
            if (added <= MAX_ITEMS_PER_CATEGORY) {
                issues.add(new Issue(Category.NEW_HEADING_ENTITY, Severity.RISK,
                        "优化稿出现第 " + added + " 处原文未支持的项目/机构标题（可疑新增事实，需人工确认）", null));
            }
        }
        addOverflowIssue(Category.NEW_HEADING_ENTITY, added, "项目/机构标题", issues);
    }

    private static void addOverflowIssue(Category category, int added, String label,
                                         List<Issue> issues) {
        if (added > MAX_ITEMS_PER_CATEGORY) {
            issues.add(new Issue(category, Severity.RISK,
                    "优化稿另有 " + (added - MAX_ITEMS_PER_CATEGORY) + " 处原文未支持的" + label
                            + "（可疑新增事实，需人工确认）", null));
        }
    }

    // ---------------------------------------------------------------- 提取与规范化

    /** 原文中的英文技术词支持集合：单词 + 相邻 2–4 词的合并形式（如 Spring Boot → springboot） */
    private static Set<String> englishTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        for (String line : nfkc(stripLinePrefixes(nullToEmpty(text))).split("\\R")) {
            Matcher matcher = FactTokenExtractor.ENGLISH_WORD.matcher(line);
            List<String> tokens = new ArrayList<>();
            while (matcher.find()) {
                tokens.add(matcher.group().toLowerCase(Locale.ROOT));
            }
            for (String token : tokens) {
                if (token.length() >= 2) {
                    terms.add(token);
                }
            }
            for (int start = 0; start < tokens.size(); start++) {
                StringBuilder merged = new StringBuilder();
                for (int end = start; end < tokens.size() && end < start + 4; end++) {
                    merged.append(tokens.get(end));
                    terms.add(merged.toString());
                }
            }
        }
        return terms;
    }

    /**
     * 证据匹配规范化：NFKC（全半角）→ 统一常见标点 → 去行首 Markdown 结构 → 去全部空白 → 小写。
     * 刻意不删除汉字/字母/数字，也不做同义替换。
     */
    static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String value = Normalizer.normalize(text, Normalizer.Form.NFKC);
        value = value.replace('\r', '\n')
                .replace('，', ',').replace('。', '.').replace('；', ';').replace('：', ':')
                .replace('、', ',').replace('（', '(').replace('）', ')')
                .replace('【', '[').replace('】', ']')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace('—', '-').replace('–', '-').replace('～', '~')
                .replace('·', '-').replace('•', '-');
        value = stripLinePrefixes(value);
        value = value.replaceAll("\\s+", "");
        return value.toLowerCase(Locale.ROOT);
    }

    /** 去掉行首的标题/列表结构符号（允许 before 带 ## / - / 1. 等格式差异） */
    private static String stripLinePrefixes(String text) {
        return LINE_PREFIX.matcher(text).replaceAll("");
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /** 全半角归一（用于数字/专名/英文词提取，避免全角字符漏检） */
    private static String nfkc(String text) {
        return Normalizer.normalize(nullToEmpty(text), Normalizer.Form.NFKC);
    }
}
