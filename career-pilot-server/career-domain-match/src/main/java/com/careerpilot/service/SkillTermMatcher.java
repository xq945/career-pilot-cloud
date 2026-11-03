package com.careerpilot.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * 技能术语匹配（《数据库设计》6.3）。
 *
 * <p>三层判定：**规范化 → 别名归一 → 边界命中**，全部为纯函数、可单测；
 * 刻意不引入同义词库与语义匹配（务实设限）。
 *
 * <p>边界规则的两个免误配保证：
 * <ul>
 *   <li>{@code Java} 不误配 {@code JavaScript}、{@code Go} 不误配 {@code Django}——拉丁术语按
 *       token 边界判定；</li>
 *   <li>{@code SpringBoot框架}、{@code 简历中的 Vue 3} 这类中英混排与带版本号的写法可正常命中——
 *       规范化剥离「空格 + 数字」版本后缀，判定再容忍术语尾随数字。</li>
 * </ul>
 */
public final class SkillTermMatcher {

    private SkillTermMatcher() {
    }

    /** 匹配档位与分值（《数据库设计》6.3 的三档） */
    public enum Hit {
        /** 直接证据（1.0） */
        DIRECT(1.0),
        /** 部分证据（0.5） */
        PARTIAL(0.5),
        /** 无证据（0） */
        MISS(0.0);

        private final double score;

        Hit(double score) {
            this.score = score;
        }

        public double score() {
            return score;
        }
    }

    /** 单条证据项：{@code term} 为原文（构造 {@link ResumeTerms} 时统一规范化），{@code source} 为来源描述 */
    public record Evidence(String term, String source) {
    }

    /** 匹配结果：档位 + 命中的证据来源（MISS 时为 null） */
    public record TermMatch(Hit hit, String source) {

        public static final TermMatch MISS = new TermMatch(Hit.MISS, null);

    }

    /** 弱证据文本命中时的账本来源标记 */
    public static final String WEAK_SOURCE = "text";

    /**
     * 简历侧证据视图：直接证据集合与弱证据文本拼接。
     *
     * <p>由调用方按维度口径组装（如项目相关性维度只传 projects 相关的证据，
     * 见《数据库设计》6.4）；构造时统一规范化并丢弃空白项。
     */
    public static final class ResumeTerms {

        private final List<Evidence> direct;

        private final String weakText;

        public ResumeTerms(List<Evidence> direct, List<String> weakTexts) {
            this.direct = direct.stream()
                    .map(evidence -> new Evidence(canonical(normalize(evidence.term())), evidence.source()))
                    .filter(evidence -> !evidence.term().isEmpty())
                    .toList();
            this.weakText = canonical(normalize(String.join("\n", weakTexts)));
        }

        List<Evidence> direct() {
            return direct;
        }

        String weakText() {
            return weakText;
        }

    }

    /**
     * 单条岗位要求术语与简历证据的匹配（《数据库设计》6.3）：
     * 直接证据集合 → 1.0；弱证据文本 → 0.5；均未命中 → 0。
     */
    public static TermMatch match(String jobTerm, ResumeTerms terms) {
        String term = canonical(normalize(jobTerm));
        if (term.isEmpty()) {
            return TermMatch.MISS;
        }
        for (Evidence evidence : terms.direct()) {
            if (hits(term, evidence.term())) {
                return new TermMatch(Hit.DIRECT, evidence.source());
            }
        }
        if (hits(term, terms.weakText())) {
            return new TermMatch(Hit.PARTIAL, WEAK_SOURCE);
        }
        return TermMatch.MISS;
    }

    // ---------------------------------------------------------------- 规范化

    /** 版本后缀：「Vue 3」「Spring Boot 3.2」中与名称以空白分隔的版本数字 */
    private static final Pattern VERSION_SUFFIX = Pattern.compile("\\s+\\d+(\\.\\d+)*");

    /** 需要删除的分隔符：空白与 . - _ / */
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-_/]");

    /**
     * 别名表（内置常量，不求全；《数据库设计》6.3 明确不引入同义词库）。
     * {@code Spring Boot ↔ SpringBoot}、{@code Node.js ↔ NodeJS} 由规范化天然覆盖，不进本表。
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("js", "javascript"),
            Map.entry("ts", "typescript"),
            Map.entry("k8s", "kubernetes"),
            Map.entry("postgres", "postgresql"),
            Map.entry("golang", "go"),
            Map.entry("vuejs", "vue"),
            Map.entry("reactjs", "react"),
            Map.entry("nodejs", "node"),
            Map.entry("nextjs", "next"));

    /**
     * 规范化（《数据库设计》6.3 第 1 条）：全角转半角 → 剥离版本后缀 → 删除空白与
     * {@code . - _ /} → 转小写。保留 {@code +} 与 {@code #}（C++、C#）。
     */
    static String normalize(String term) {
        if (term == null) {
            return "";
        }
        String text = toHalfWidth(term);
        text = VERSION_SUFFIX.matcher(text).replaceAll("");
        text = SEPARATORS.matcher(text).replaceAll("");
        return text.toLowerCase(Locale.ROOT);
    }

    /** 别名归一：命中别名表即替换为规范名（双向成立——规范名自身不在表中，getOrDefault 返回原值） */
    private static String canonical(String normalized) {
        return ALIASES.getOrDefault(normalized, normalized);
    }

    /** 全角 → 半角：ASCII 可见字符区间平移，全角空格转半角空格 */
    private static String toHalfWidth(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toCharArray()) {
            if (c == '　') {
                sb.append(' ');
            }
            else if (c >= '！' && c <= '～') {
                sb.append((char) (c - 0xFEE0));
            }
            else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 命中判定

    /** 拉丁术语：规范化后仅由 a-z0-9+# 构成（此类走 token 边界；其余按双向包含） */
    private static final Pattern LATIN_TERM = Pattern.compile("[a-z0-9+#]+");

    /** 边界正则缓存（术语数有限；评分在请求路径上，避免重复编译） */
    private static final Map<String, Pattern> LATIN_PATTERNS = new ConcurrentHashMap<>();

    /**
     * 单条证据串是否命中该术语（两者均已规范化）。
     *
     * <p>拉丁术语用 token 边界并**容忍尾随版本数字**（{@code Vue} 命中 {@code Vue3}）；
     * 含中文的术语用双向包含（{@code SpringBoot框架} 命中 {@code SpringBoot}）。
     */
    private static boolean hits(String term, String evidence) {
        if (evidence.isEmpty()) {
            return false;
        }
        if (term.equals(evidence)) {
            return true;
        }
        if (LATIN_TERM.matcher(term).matches()) {
            return patternOf(term).matcher(evidence).find();
        }
        return evidence.contains(term) || term.contains(evidence);
    }

    private static Pattern patternOf(String term) {
        return LATIN_PATTERNS.computeIfAbsent(term, key -> Pattern.compile(
                "(?<![a-z0-9+#])" + Pattern.quote(key) + "\\d*(?![a-z0-9+#])"));
    }

}
