package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerpilot.service.optimization.numeric.NumericMention;
import com.careerpilot.service.optimization.numeric.NumericMentionExtractor;

/**
 * 块级事实 token 提取（修复 C，阶段 1）。
 *
 * <p>与顶层事实校验共享同一套口径：专名、英文技术词、职责升级短语的规则集中在这里，
 * {@code ResumeOptimizationFactChecker} 直接引用，避免两个守卫各自维护一份正则/词表后
 * 产生矛盾。数字与日期不重新解析，直接适配 {@link NumericMentionExtractor} 的结果
 * （Repair B 保留为唯一数字来源）。
 *
 * <p>token 只用于文档模型/诊断，不单独充当“事实已证明”的依据。
 */
public final class FactTokenExtractor {

    /** 带机构/竞赛/证书后缀的专名候选（核心部分用于与原文比对） */
    public static final Pattern PROPER_NOUN = Pattern.compile(
            "([\\u4e00-\\u9fa5A-Za-z0-9·]{2,20})(?:公司|集团|科技|大学|学院|学校|研究院|实验室|大赛|竞赛|证书|认证)");

    /** 英文技术名词候选（字母与 +#，数字由数字校验单独处理） */
    public static final Pattern ENGLISH_WORD = Pattern.compile("[A-Za-z][A-Za-z+#]*");

    /**
     * 英文停用词：避免把普通英文连接词/文档词当成“新增技术名词”。
     * AI / Agent 等不在此列——它们是否新增由原文是否出现决定。
     */
    public static final Set<String> ENGLISH_STOP_WORDS = Set.of(
            "a", "an", "and", "or", "the", "of", "in", "on", "at", "for", "to", "with", "by",
            "from", "as", "is", "are", "was", "were", "be", "been", "this", "that", "these",
            "those", "it", "its", "we", "you", "your", "our", "their", "using", "use", "used",
            "based", "via", "per", "etc", "skills", "skill", "knowledge", "base", "markdown",
            "json", "http", "https",
            "name", "email", "phone", "project", "projects", "experience", "education",
            "certificate", "summary", "profile", "objective", "company", "school", "university",
            "major", "degree", "period", "role", "description", "technology", "technologies",
            "framework", "database", "server", "system", "platform", "service", "services",
            "design", "development", "developer", "engineer", "intern", "internship", "team",
            "communication", "leadership", "ability");

    /**
     * 职责/成果升级短语：只在 before 未出现、且原文全文也未出现时标记风险。
     * 不含“提升/降低”等普通性能动词——量化信息由数字校验负责，避免把合理润色误报。
     */
    public static final List<String> OWNERSHIP_ESCALATIONS = List.of(
            "主导", "牵头", "独立负责", "独立完成", "带领", "从0到1", "从0到１", "负责");

    /**
     * 专名正则的贪婪前缀词：模型常写“持有中级软件设计师证书”“学校：江西师范大学”，
     * 这些字段/动词不属于专名本身。剥离后再比较，避免把整段前缀当作新专名。
     */
    private static final List<String> PROPER_NOUN_PREFIXES = List.of(
            "持有", "获得", "荣获", "获评", "取得", "颁发", "参与", "负责", "主导", "完成",
            "通过", "学校", "公司", "专业", "姓名", "性别", "年龄", "学历", "电话", "邮箱",
            "住址", "地址", "教育", "经历", "项目", "证书", "认证", "在", "于", "的");

    private final NumericMentionExtractor numericExtractor = new NumericMentionExtractor();

    /** 去掉专名前常见的字段标签/动词前缀；保证剩余核心至少 2 个字符。 */
    public static String properNounCore(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.strip();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String prefix : PROPER_NOUN_PREFIXES) {
                if (value.startsWith(prefix) && value.length() > prefix.length() + 1) {
                    value = value.substring(prefix.length());
                    changed = true;
                }
            }
        }
        return value;
    }

    /**
     * 专名是否被原文支持。
     *
     * <p>正则可能把连接词一起吞进来（“并在蓝桥杯等编程”“JWT无状态”），
     * 因此先看完整值，再退而检查长度 ≥3 的子串；长度 2 的子串不检查，
     * 避免“大学/公司”这类通用后缀把全新机构名放过去。
     */
    public static boolean properNounSupported(String sourceCanonical, String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        if (sourceCanonical.contains(value)) {
            return true;
        }
        for (int length = value.length() - 1; length >= 3; length--) {
            for (int start = 0; start + length <= value.length(); start++) {
                if (sourceCanonical.contains(value.substring(start, start + length))) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * @param text         块的 factText（已 NFKC；表格行已转换为 {@code 表头: 值}）
     * @param mentions     同一文本的数字/日期提及（复用 Repair B 的提取结果）
     * @param projectKey   块内项目标识；空字符串表示没有可识别项目
     */
    List<FactToken> extract(String text, List<NumericMention> mentions, String projectKey) {
        String value = text == null ? "" : text;
        Map<String, FactToken> tokens = new LinkedHashMap<>();

        for (NumericMention mention : mentions) {
            FactToken.Kind kind = mention.kind() == NumericMention.Kind.DATE
                    ? FactToken.Kind.DATE : FactToken.Kind.NUMBER;
            add(tokens, new FactToken(kind, mention.value(), mention.start(), mention.end()));
        }

        Matcher proper = PROPER_NOUN.matcher(value);
        while (proper.find()) {
            String canonical = CoverageText.canonical(properNounCore(proper.group(1)));
            if (canonical.length() >= 2) {
                add(tokens, new FactToken(FactToken.Kind.PROPER_NOUN, canonical,
                        proper.start(1), proper.end(1)));
            }
        }

        Matcher english = ENGLISH_WORD.matcher(value);
        while (english.find()) {
            String token = english.group().toLowerCase(Locale.ROOT);
            if (token.length() >= 2 && !ENGLISH_STOP_WORDS.contains(token)) {
                add(tokens, new FactToken(FactToken.Kind.TECH_TERM, token,
                        english.start(), english.end()));
            }
        }

        for (String phrase : OWNERSHIP_ESCALATIONS) {
            int index = value.indexOf(phrase);
            if (index >= 0) {
                add(tokens, new FactToken(FactToken.Kind.OWNERSHIP,
                        CoverageText.canonical(phrase), index, index + phrase.length()));
            }
        }

        if (projectKey != null && !projectKey.isEmpty()) {
            add(tokens, new FactToken(FactToken.Kind.PROJECT, projectKey, -1, -1));
        }
        return new ArrayList<>(tokens.values());
    }

    /** 同类同值 token 只保留第一次出现，避免重复词撑大账本。 */
    private static void add(Map<String, FactToken> tokens, FactToken token) {
        if (token.value() == null || token.value().isEmpty()) {
            return;
        }
        tokens.putIfAbsent(token.kind() + "|" + token.value(), token);
    }

    /** 供测试/诊断：把 token 值按类型取出（不含位置）。 */
    Set<String> values(List<FactToken> tokens, FactToken.Kind kind) {
        Set<String> result = new LinkedHashSet<>();
        for (FactToken token : tokens) {
            if (token.kind() == kind) {
                result.add(token.value());
            }
        }
        return result;
    }
}
