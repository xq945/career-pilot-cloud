package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.numeric.NumericLexicons;
import com.careerpilot.service.optimization.numeric.NumericMention;
import com.careerpilot.service.optimization.numeric.NumericMentionExtractor;
import com.careerpilot.service.optimization.numeric.TextRange;

/**
 * 正文事实覆盖与变更账本（修复 C，阶段 1）。
 *
 * <p>目标不是证明语义真实性，而是保证：
 * <ul>
 *   <li>优化稿每个事实性块能追溯到原文同章节块，或被一条可定位的 change 覆盖；</li>
 *   <li>每条 change 最多消费一次；重复/无关/未使用的 change 不能充当覆盖证据；</li>
 *   <li>change 只覆盖它实际声明的差异：同一块内未被 before/after 覆盖的文本差异
 *       记 {@code UNCOVERED_DIFF}，不会被 change 顺带放行；</li>
 *   <li>删除事实、跨章节搬运、新章节、新内容词默认阻断；</li>
 *   <li>重复/相似的候选块无法唯一判定时记 {@code AMBIGUOUS}，不任选一个来源；</li>
 *   <li>结构无法可靠解析且发生变化的块记 {@code UNPARSABLE}，不默认通过；</li>
 *   <li>纯格式/已证明等价的块通过，并告诉数字守卫哪些区间可跳过（避免行签名误报）。</li>
 * </ul>
 *
 * <p>匹配有明确边界：块数超过 {@link DocumentParser#MAX_BLOCKS} 或单块等价候选
 * 超过 {@link #MAX_EQUIVALENT_CANDIDATES} 时按不可解析/歧义阻断，不做无界模糊匹配。
 *
 * <p>不依赖 Spring、不调用模型、不访问数据库；不输出正文。
 */
public final class DocumentCoverageGuard {

    public enum Code {
        NEW_FACT,
        NEW_SECTION,
        DELETED_FACT,
        UNCOVERED_DIFF,
        UNCOVERED_FACT,
        SECTION_MISMATCH,
        MOVE_UNSUPPORTED,
        CHANGE_UNUSED,
        CHANGE_DUPLICATE,
        AMBIGUOUS,
        UNPARSABLE,
        NEW_CONTENT_WORDING,
        FACT_CHANGED
    }

    public record Finding(Code code, int blockIndex, Integer changeIndex) {
    }

    public record Audit(List<Finding> findings,
                        List<TextRange> ignoredResume,
                        List<TextRange> ignoredOptimized,
                        Map<String, String> sectionMap) {
    }

    private static final Set<String> GENERIC_WORDS = Set.of(
            "负责", "参与", "完成", "进行", "实现", "使用", "开发", "设计", "优化", "提升",
            "降低", "增加", "减少", "支持", "主要", "相关", "业务", "项目", "工作", "经历",
            "内容", "成果", "时间", "系统", "平台", "技术", "能力", "经验", "团队", "沟通",
            "协作", "学习", "掌握", "熟悉", "了解", "独立", "返回", "至今", "数据", "接口",
            "模块", "页面", "服务", "功能", "需求", "测试", "部署", "维护", "管理", "分析",
            "解决", "问题", "提高", "构建", "性能", "质量", "效率", "响应", "证书", "认证",
            "大学", "公司", "学校", "学院", "研究院", "实验室", "二级", "三级", "一级");

    /** 单块等价匹配最多考察的候选数；超过后不再“择优”，直接按歧义阻断。 */
    private static final int MAX_EQUIVALENT_CANDIDATES = 16;

    /** 单个目标块最多扫描的同章节源块数；超过后保守按歧义阻断。 */
    private static final int MAX_SCAN_PER_TARGET = 400;

    /** 标题重命名的包含规则最小长度：过短标题（项目 A / AB）不做包含推断。 */
    private static final int MIN_RENAME_LENGTH = 3;

    /** 标题重命名允许的受控后缀/前缀长度上限（canonical 字符数）。 */
    private static final int MAX_RENAME_SUFFIX = 16;

    /**
     * 允许“摘要型新增章节”的受控集合：原文没有这些章节时，优化稿可以新增，
     * 但其中的数字/日期/专名/技术词/职责短语必须能在原文中找到。
     */
    private static final Set<String> SUMMARY_SECTIONS = Set.of(
            "个人总结", "个人优势", "自我评价", "求职意向",
            // 原文把教育信息写在“基本信息”里、优化稿重组为独立章节：按事实 token 校验。
            "教育经历");

    private final DocumentParser parser = new DocumentParser();

    private final NumericMentionExtractor extractor = new NumericMentionExtractor();

    private final FactTokenExtractor factTokenExtractor = new FactTokenExtractor();

    public Audit check(String resumeContent, String optimizedContent, List<ChangeItem> changes) {
        SectionedDocument source = parser.parse(resumeContent);
        SectionedDocument target = parser.parse(optimizedContent);
        List<Finding> findings = new ArrayList<>();
        List<TextRange> ignoredSource = new ArrayList<>();
        List<TextRange> ignoredTarget = new ArrayList<>();

        // 联系方式/基本信息字段/通用结构不参与事实覆盖，也不应进入数字三态比较
        // （电话、年龄等字段的拆分/合并不是业务数字篡改）。
        for (CoverageBlock block : source.blocks) {
            if (block.nonFact) {
                ignoredSource.add(new TextRange(block.rawStart, block.rawEnd));
            }
        }
        for (CoverageBlock block : target.blocks) {
            if (block.nonFact) {
                ignoredTarget.add(new TextRange(block.rawStart, block.rawEnd));
            }
        }

        // 解析被截断时不能把“剩余部分”当作完整文档：直接按不可解析阻断。
        if (!source.complete || !target.complete) {
            findings.add(new Finding(Code.UNPARSABLE, 0, null));
            return new Audit(List.copyOf(findings), List.of(), List.of(), Map.of());
        }

        Set<String> allowed = allowedVocabulary(source);

        List<ChangeRef> refs = new ArrayList<>();
        if (changes != null) {
            for (int i = 0; i < changes.size(); i++) {
                ChangeItem item = changes.get(i);
                if (item == null || item.getBefore() == null || item.getAfter() == null) {
                    continue;
                }
                refs.add(new ChangeRef(i, item));
            }
        }
        detectDuplicates(refs, findings);

        // 章节映射：目标章节可以带多余的父级（如姓名标题 # 张广），
        // 也可以把项目行升级为 ### 标题；逐级回退到源文档中存在的章节。
        Map<String, String> sourceToTarget = new LinkedHashMap<>();
        Map<String, String> targetToSource = new LinkedHashMap<>();
        for (String targetKey : target.sectionKeys) {
            String sourceKey = resolveSourceSection(targetKey, source.sectionKeys);
            if (sourceKey != null) {
                targetToSource.put(targetKey, sourceKey);
                sourceToTarget.putIfAbsent(sourceKey, targetKey);
            }
        }

        Set<String> sourceFactKeys = sourceFactKeys(source);
        Set<String> sourceTokenKeys = sourceTokenKeys(source);
        String sourceCanonical = CoverageText.canonical(source.text);

        // 1) 定位全部 change；REORDER 只表示结构顺序，块匹配负责事实不变
        for (ChangeRef ref : refs) {
            if (ref.duplicate) {
                continue;
            }
            if ("REORDER".equalsIgnoreCase(ref.item.getType())) {
                ref.reorder = true;
                continue;
            }
            ref.source = locate(source, ref.beforeText);
            ref.target = locate(target, ref.afterText);
            if (ref.source == null || ref.target == null) {
                continue;
            }
            ref.sourceSection = sectionOf(source, ref.source);
            ref.targetSection = sectionOf(target, ref.target);
            if (ref.sourceSection == null || ref.targetSection == null) {
                findings.add(new Finding(Code.AMBIGUOUS, ref.source.startBlock(), ref.index));
                ref.blocked = true;
                ref.used = true;
            }
        }

        // 2) 先处理标题重命名，建立 Section 映射；再处理普通 change
        for (ChangeRef ref : refs) {
            if (ref.duplicate || ref.used || ref.source == null || ref.target == null) {
                continue;
            }
            if (!isHeadingPair(source, ref.source, target, ref.target)) {
                continue;
            }
            String sourceTitle = source.blocks.get(ref.source.startBlock()).canonicalText;
            String targetTitle = target.blocks.get(ref.target.startBlock()).canonicalText;
            if (sourceTitle.equals(targetTitle)) {
                continue;
            }
            if (canRenameHeading(source, ref.source, target, ref.target)) {
                sourceToTarget.put(ref.sourceSection, ref.targetSection);
                targetToSource.put(ref.targetSection, ref.sourceSection);
                ref.used = true;
                markCovered(source, ref.source, ref.coveredSource);
                markCovered(target, ref.target, ref.coveredTarget);
                addIgnored(source, ref.source, ignoredSource);
                addIgnored(target, ref.target, ignoredTarget);
            }
            // 不合法的标题改名：不映射、不覆盖；目标标题会成为新章节
        }
        for (ChangeRef ref : refs) {
            if (ref.duplicate || ref.used || ref.source == null || ref.target == null) {
                continue;
            }
            if (isHeadingPair(source, ref.source, target, ref.target)) {
                String sourceTitle = source.blocks.get(ref.source.startBlock()).canonicalText;
                String targetTitle = target.blocks.get(ref.target.startBlock()).canonicalText;
                if (!sourceTitle.equals(targetTitle)) {
                    // 已经在上一步判定为不合法改名，不能作为正文覆盖
                    continue;
                }
            }
            processChange(ref, source, target, targetToSource, allowed, findings,
                    ignoredSource, ignoredTarget, ref.coveredSource, ref.coveredTarget,
                    sourceFactKeys, sourceTokenKeys, sourceCanonical);
        }

        // 块匹配：先精确，再事实等价（格式/同义改写）。
        // 多候选不能唯一定位时记 AMBIGUOUS，不任选一个来源放行。
        Set<Integer> matchedSource = new HashSet<>();
        Set<Integer> matchedTarget = new HashSet<>();
        for (int t = 0; t < target.blocks.size(); t++) {
            final int targetIndex = t;
            if (refs.stream().anyMatch(r -> r.coveredTarget.contains(targetIndex))) {
                continue;
            }
            CoverageBlock tb = target.blocks.get(t);
            // 通用字段标签/联系方式等 nonFact 块不参与事实追溯，也不制造候选歧义。
            if (tb.nonFact) {
                continue;
            }
            String sourceSection = targetToSource.get(tb.sectionKey);
            if (sourceSection == null) {
                continue;
            }
            MatchResult exact = findExact(source, sourceSection, tb, matchedSource, refs);
            if (exact.ambiguous()) {
                matchedTarget.add(t);
                matchedSource.addAll(exact.candidates());
                findings.add(new Finding(Code.AMBIGUOUS, t, null));
            }
            else if (exact.index() >= 0) {
                matchedTarget.add(t);
                matchedSource.add(exact.index());
                addIgnoredPair(source.blocks.get(exact.index()), tb, ignoredSource, ignoredTarget);
            }
        }
        for (int t = 0; t < target.blocks.size(); t++) {
            if (matchedTarget.contains(t) || coveredBy(refs, t, true)) {
                continue;
            }
            CoverageBlock tb = target.blocks.get(t);
            if (tb.nonFact || tb.isHeading() || !tb.isReliable()) {
                continue;
            }
            String sourceSection = targetToSource.get(tb.sectionKey);
            if (sourceSection == null) {
                continue;
            }
            MatchResult equivalent = findEquivalent(source, sourceSection, tb,
                    matchedSource, refs, allowed);
            if (equivalent.ambiguous()) {
                matchedTarget.add(t);
                matchedSource.addAll(equivalent.candidates());
                findings.add(new Finding(Code.AMBIGUOUS, t, null));
            }
            else if (equivalent.index() >= 0) {
                matchedTarget.add(t);
                matchedSource.add(equivalent.index());
                addIgnoredPair(source.blocks.get(equivalent.index()), tb,
                        ignoredSource, ignoredTarget);
            }
        }

        // 未匹配的目标块
        for (int t = 0; t < target.blocks.size(); t++) {
            if (matchedTarget.contains(t) || coveredBy(refs, t, true)) {
                continue;
            }
            CoverageBlock tb = target.blocks.get(t);
            if (tb.nonFact) {
                continue;
            }
            // 结构不可靠且与原文没有“规范化后完全相同”的对应块：不猜，明确报不可解析。
            if (!tb.isReliable()) {
                findings.add(new Finding(Code.UNPARSABLE, t, null));
                continue;
            }
            if (tb.isHeading()) {
                // 姓名标题、项目行升级为 ### 标题：文本本身已在原文出现，
                // 属于结构性格式变化，不当作新章节；新项目名仍会命中 NEW_SECTION。
                if (structuralHeading(tb, sourceCanonical)) {
                    continue;
                }
                findings.add(new Finding(Code.NEW_SECTION, t, null));
                continue;
            }
            // 摘要型新增章节：只允许由原文事实 token 组成的概括。
            if (isSummarySection(tb.sectionKey)) {
                if (summaryBlockSupported(tb, sourceFactKeys, sourceTokenKeys, sourceCanonical)) {
                    ignoredTarget.add(new TextRange(tb.rawStart, tb.rawEnd));
                    continue;
                }
                findings.add(new Finding(tb.hasFacts()
                        ? Code.NEW_FACT : Code.NEW_CONTENT_WORDING, t, null));
                continue;
            }
            if (tb.hasFacts()) {
                findings.add(new Finding(Code.NEW_FACT, t, null));
                continue;
            }
            List<String> newWords = newContentWords(tb.contentWords, allowed);
            findings.add(new Finding(newWords.isEmpty()
                    ? Code.UNCOVERED_DIFF : Code.NEW_CONTENT_WORDING, t, null));
        }

        // 未匹配/未覆盖的源块（删除事实默认阻断；纯非事实块允许）
        Set<String> matchedSourceCanonical = new HashSet<>();
        for (int index : matchedSource) {
            matchedSourceCanonical.add(source.blocks.get(index).canonicalText);
        }
        for (int s = 0; s < source.blocks.size(); s++) {
            if (matchedSource.contains(s) || coveredBy(refs, s, false)) {
                continue;
            }
            CoverageBlock sb = source.blocks.get(s);
            if (sb.nonFact) {
                continue;
            }
            if (matchedSourceCanonical.contains(sb.canonicalText)) {
                // 同一事实的重复出现被去重：允许删除多余副本
                continue;
            }
            if (sb.hasFacts()) {
                findings.add(new Finding(Code.DELETED_FACT, s, null));
            }
            else if (sb.isHeading()) {
                findings.add(new Finding(Code.DELETED_FACT, s, null));
            }
            else {
                findings.add(new Finding(Code.UNCOVERED_DIFF, s, null));
            }
        }

        // 未使用 change：存在实质改动/阻断时一律不允许；文档本身完全等价时容忍
        boolean material = refs.stream().anyMatch(r -> r.used && !r.blocked
                && !isNoOpChange(r.beforeText, r.afterText));
        boolean hasBlockFinding = findings.stream().anyMatch(f -> f.code() != Code.CHANGE_DUPLICATE);
        for (ChangeRef ref : refs) {
            if (ref.duplicate || ref.used || ref.reorder) {
                continue;
            }
            // 纯格式 no-op（编号/制表符/末尾标点）不充当覆盖证据，但也不作为
            // “无关记录”阻断：正文事实变化由块匹配与事实残差独立校验。
            if (isNoOpChange(ref.beforeText, ref.afterText)) {
                continue;
            }
            if (material || hasBlockFinding) {
                findings.add(new Finding(Code.CHANGE_UNUSED, -1, ref.index));
            }
        }

        // sectionMap 是“目标章节 → 源章节”，供日期守卫直接按目标块查映射；
        // 同一源章节可能对应多个目标子章节，因此不能反向压缩成一对一。
        return new Audit(List.copyOf(findings), List.copyOf(ignoredSource),
                List.copyOf(ignoredTarget), Map.copyOf(targetToSource));
    }

    // ---------------------------------------------------------------- change 处理

    private void processChange(ChangeRef ref, SectionedDocument source, SectionedDocument target,
                               Map<String, String> targetToSource, Set<String> allowed,
                               List<Finding> findings,
                               List<TextRange> ignoredSource, List<TextRange> ignoredTarget,
                               Set<Integer> coveredSource, Set<Integer> coveredTarget,
                               Set<String> sourceFactKeys, Set<String> sourceTokenKeys,
                               String sourceCanonical) {
        if (ref.duplicate) {
            return;
        }
        if (!matchesSectionLabel(ref.item.getSection(), ref.sourceSection, source)) {
            findings.add(new Finding(Code.SECTION_MISMATCH, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            return;
        }
        // 摘要型新增章节（原文没有个人总结等）：不要求与源章节相同，
        // 但 after 中的事实 token 必须全部能在原文中找到；不消费正文覆盖区间。
        if (containsSummary(target, ref.target)) {
            if (summaryTextSupported(ref.afterText, sourceFactKeys, sourceTokenKeys, sourceCanonical)) {
                ref.used = true;
            }
            else {
                findings.add(new Finding(Code.NEW_CONTENT_WORDING,
                        ref.source.startBlock(), ref.index));
                ref.blocked = true;
                ref.used = true;
            }
            return;
        }
        String logicalTarget = targetToSource.getOrDefault(ref.targetSection, ref.targetSection);
        if (!ref.sourceSection.equals(logicalTarget)) {
            findings.add(new Finding(Code.MOVE_UNSUPPORTED, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        // 纯 no-op 的 change 不产生覆盖证据，也不做事实/内容词校验；
        // 若正文存在实质差异，未使用规则仍会阻断它充当证据。
        // 注意：行拆分/合并的行结构变化不是 no-op——规范化会把换行抹平，
        // 因此这里额外比较行形状（保留行边界）。
        if (isNoOpChange(ref.beforeText, ref.afterText)) {
            return;
        }

        List<NumericMention> beforeFacts = extractor.extract(
                factTextForChange(ref.beforeText));
        List<NumericMention> afterFacts = extractor.extract(
                factTextForChange(ref.afterText));
        if (!factsEquivalent(beforeFacts, afterFacts)) {
            findings.add(new Finding(Code.FACT_CHANGED, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        List<String> newWords = newContentWords(
                contentWordsWithoutFacts(factTextForChange(ref.afterText)), allowed);
        if (!newWords.isEmpty()) {
            findings.add(new Finding(Code.NEW_CONTENT_WORDING,
                    ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        // 文本残差：change 只覆盖它实际声明的差异。同一块内若还有其他文本变化
        // （例如声明了 A→A'，块里另一处 B 也被改写），不能被这条 change 顺带放行。
        if (!residualTextEquivalent(source, ref.source, ref.beforeText,
                target, ref.target, ref.afterText)) {
            findings.add(new Finding(Code.UNCOVERED_DIFF, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        // 事实残差：change 只覆盖它实际声明的差异，同一块内其他事实不能被顺带放行
        List<NumericMention> sourceUnion = unionFacts(source.blocks, ref.source);
        List<NumericMention> targetUnion = unionFacts(target.blocks, ref.target);
        if (!residueEquivalent(sourceUnion, beforeFacts, targetUnion, afterFacts)) {
            findings.add(new Finding(Code.UNCOVERED_FACT, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        // 多项目块：整体事实多重集相同不代表项目归属相同（A/B 互换）。
        // 有项目标识时按项目分组比较；不匹配则不把区间交给数字守卫忽略。
        Map<String, List<NumericMention>> sourceGroups = projectGroups(source.blocks, ref.source);
        Map<String, List<NumericMention>> targetGroups = projectGroups(target.blocks, ref.target);
        if (!groupsEquivalent(sourceGroups, targetGroups)) {
            findings.add(new Finding(Code.FACT_CHANGED, ref.source.startBlock(), ref.index));
            ref.blocked = true;
            ref.used = true;
            markCovered(source, ref.source, coveredSource);
            markCovered(target, ref.target, coveredTarget);
            return;
        }

        ref.used = true;
        markCovered(source, ref.source, coveredSource);
        markCovered(target, ref.target, coveredTarget);
        addIgnored(source, ref.source, ignoredSource);
        addIgnored(target, ref.target, ignoredTarget);
    }

    private boolean matchesSectionLabel(String label, String sourceSection, SectionedDocument source) {
        String normalized = CoverageText.matchNormalize(label);
        if (normalized.isEmpty()) {
            return true;
        }
        if (matchesKey(normalized, sourceSection)) {
            return true;
        }
        boolean labelPointsElsewhere = source.sectionKeys.stream()
                .anyMatch(key -> matchesKey(normalized, key)
                        || key.endsWith("/" + normalized));
        return !labelPointsElsewhere;
    }

    private static boolean matchesKey(String normalized, String key) {
        if (key == null) {
            return false;
        }
        if (key.equals(normalized) || key.endsWith("/" + normalized)
                || normalized.endsWith("/" + key)
                || (key.isEmpty() && normalized.isEmpty())) {
            return true;
        }
        for (String part : key.split("/")) {
            if (part.equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把目标章节解析到源章节：
     * <ol>
     *   <li>逐级去掉尾部父章节，回退到源中存在的最近祖先（处理 # 姓名标题造成的多余前缀）；</li>
     *   <li>尝试任意连续子路径（处理“项目行上行成 ### 标题”）；</li>
     *   <li>同长度出现多个不同候选 → 无法唯一定位，返回 null（保守阻断）。</li>
     * </ol>
     */
    private static String resolveSourceSection(String targetKey, Set<String> sourceKeys) {
        // 目标没有章节（纯文本重排）：源只有一个顶层章节时视为该章节，
        // 多个顶层章节则无法判断，保持 null（后续按块级唯一匹配处理）。
        if (targetKey.isEmpty()) {
            if (sourceKeys.contains("")) {
                return "";
            }
            String only = null;
            for (String sourceKey : sourceKeys) {
                if (sourceKey.isEmpty() || sourceKey.contains("/")) {
                    continue;
                }
                if (only != null) {
                    return null;
                }
                only = sourceKey;
            }
            return only;
        }
        String key = targetKey;
        while (true) {
            if (sourceKeys.contains(key)) {
                return key;
            }
            int slash = key.lastIndexOf('/');
            if (slash < 0) {
                break;
            }
            key = key.substring(0, slash);
        }
        String[] parts = targetKey.split("/");
        for (int length = parts.length; length >= 1; length--) {
            String best = null;
            for (int start = 0; start + length <= parts.length; start++) {
                StringBuilder candidate = new StringBuilder();
                for (int i = start; i < start + length; i++) {
                    if (candidate.length() > 0) {
                        candidate.append('/');
                    }
                    candidate.append(parts[i]);
                }
                if (!sourceKeys.contains(candidate.toString())) {
                    continue;
                }
                if (best != null) {
                    return null;
                }
                best = candidate.toString();
            }
            if (best != null) {
                return best;
            }
        }
        // 源文档没有章节结构（只有根 section）：目标章节无法对比，按根处理；
        // 块级事实/项目身份匹配仍然生效。
        return sourceKeys.contains("") ? "" : null;
    }

    /** 章节路径的任一级是否属于受控的“摘要型新增章节”。 */
    static boolean isSummarySection(String sectionKey) {
        if (sectionKey == null || sectionKey.isEmpty()) {
            return false;
        }
        for (String part : sectionKey.split("/")) {
            if (SUMMARY_SECTIONS.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsSummary(SectionedDocument target, Located located) {
        if (located == null || located.startBlock() < 0) {
            return false;
        }
        for (int i = located.startBlock(); i <= located.endBlock()
                && i < target.blocks.size(); i++) {
            if (isSummarySection(target.blocks.get(i).sectionKey)) {
                return true;
            }
        }
        return false;
    }

    /** 标题文本本身已在原文出现：结构性标题（姓名/项目行升级），不是新章节。 */
    private static boolean structuralHeading(CoverageBlock target, String sourceCanonical) {
        String canonical = target.canonicalText;
        return canonical.length() >= 2 && sourceCanonical.contains(canonical);
    }

    /**
     * 摘要/重组章节的事实来源集合：直接在原文全文上提取，
     * 而不是只看非联系方式块——教育/联系方式字段里的年份也可能被摘要引用。
     */
    private Set<String> sourceFactKeys(SectionedDocument source) {
        Set<String> keys = new LinkedHashSet<>();
        for (NumericMention mention : extractor.extract(source.text)) {
            keys.add(factKey(mention));
        }
        return keys;
    }

    private static Set<String> sourceTokenKeys(SectionedDocument source) {
        Set<String> keys = new LinkedHashSet<>();
        for (CoverageBlock block : source.blocks) {
            for (FactToken token : block.factTokens) {
                keys.add(token.kind() + "|" + token.value());
            }
        }
        return keys;
    }

    /**
     * 摘要块只允许原文存在的事实 token（数字/日期/专名/技术词/职责短语）；
     * 普通概括性措辞不逐词限制，但职责升级短语不能凭空出现。
     */
    private boolean summaryBlockSupported(CoverageBlock block, Set<String> sourceFactKeys,
                                          Set<String> sourceTokenKeys, String sourceCanonical) {
        for (NumericMention mention : block.numericMentions) {
            if (!sourceFactKeys.contains(factKey(mention)) && !isSummaryCount(mention)) {
                return false;
            }
        }
        return summaryTokensSupported(block.factTokens, sourceTokenKeys, sourceCanonical);
    }

    private boolean summaryTextSupported(String afterText, Set<String> sourceFactKeys,
                                         Set<String> sourceTokenKeys, String sourceCanonical) {
        String text = factTextForChange(afterText);
        List<NumericMention> facts = extractor.extract(text);
        for (NumericMention mention : facts) {
            if (!sourceFactKeys.contains(factKey(mention)) && !isSummaryCount(mention)) {
                return false;
            }
        }
        return summaryTokensSupported(factTokenExtractor.extract(text, facts, ""),
                sourceTokenKeys, sourceCanonical);
    }

    /**
     * 摘要型章节允许“两个项目”这类概括计数：源文通常不会逐字写“两个”。
     * 只放宽 COUNT；百分比/金额/时长/日期/版本仍必须逐项在原文中找到。
     * 这是摘要章节的受控放宽，正文块不适用。
     */
    private static boolean isSummaryCount(NumericMention mention) {
        return mention.kind() == NumericMention.Kind.COUNT;
    }

    private static boolean summaryTokensSupported(List<FactToken> tokens, Set<String> sourceTokenKeys,
                                                  String sourceCanonical) {
        for (FactToken token : tokens) {
            if (token.kind() == FactToken.Kind.NUMBER || token.kind() == FactToken.Kind.DATE
                    || token.kind() == FactToken.Kind.PROJECT) {
                continue;
            }
            if (sourceTokenKeys.contains(token.kind() + "|" + token.value())) {
                continue;
            }
            if (token.kind() == FactToken.Kind.PROPER_NOUN
                    ? FactTokenExtractor.properNounSupported(sourceCanonical, token.value())
                    : sourceCanonical.contains(token.value())) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static String factKey(NumericMention mention) {
        return mention.kind() + "|" + mention.value() + "|" + mention.unit();
    }

    // ---------------------------------------------------------------- 定位

    private static final class ChangeRef {
        private final int index;
        private final ChangeItem item;
        /** 已剥离 Prompt 标签的证据文本；定位/事实比较统一使用这一份。 */
        private final String beforeText;
        private final String afterText;
        private Located source;
        private Located target;
        private String sourceSection;
        private String targetSection;
        private boolean used;
        private boolean blocked;
        private boolean duplicate;
        private boolean reorder;
        private final Set<Integer> coveredSource = new HashSet<>();
        private final Set<Integer> coveredTarget = new HashSet<>();

        private ChangeRef(int index, ChangeItem item) {
            this.index = index;
            this.item = item;
            this.beforeText = PromptTags.strip(item.getBefore());
            this.afterText = PromptTags.strip(item.getAfter());
        }
    }

    private record Located(int startBlock, int endBlock) {
    }

    private Located locate(SectionedDocument doc, String needle) {
        String normalized = CoverageText.matchNormalize(needle);
        if (normalized.isEmpty()) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        List<int[]> spans = new ArrayList<>();
        for (CoverageBlock block : doc.blocks) {
            int start = text.length();
            text.append(block.matchText);
            spans.add(new int[]{start, text.length()});
        }
        String haystack = text.toString();
        int from = 0;
        List<Located> matches = new ArrayList<>();
        while (true) {
            int position = haystack.indexOf(normalized, from);
            if (position < 0) {
                break;
            }
            int end = position + normalized.length();
            int startBlock = blockAt(spans, position);
            int endBlock = blockAt(spans, Math.max(position, end - 1));
            if (startBlock >= 0 && endBlock >= 0) {
                Located located = new Located(startBlock, endBlock);
                if (!matches.contains(located)) {
                    matches.add(located);
                }
            }
            from = position + Math.max(1, normalized.length());
        }
        if (matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        // 多个位置：无法可靠消歧 → 交给调用方标记 AMBIGUOUS（但从安全角度不能任选）
        return new Located(-1, -1);
    }

    private static int blockAt(List<int[]> spans, int position) {
        for (int i = 0; i < spans.size(); i++) {
            int[] span = spans.get(i);
            if (position >= span[0] && position < span[1]) {
                return i;
            }
        }
        return -1;
    }

    private String sectionOf(SectionedDocument doc, Located located) {
        if (located.startBlock() < 0 || located.endBlock() < located.startBlock()) {
            return null;
        }
        String key = null;
        for (int i = located.startBlock(); i <= located.endBlock() && i < doc.blocks.size(); i++) {
            CoverageBlock block = doc.blocks.get(i);
            if (key == null) {
                key = block.sectionKey;
            }
            else if (!key.equals(block.sectionKey)) {
                return null;
            }
        }
        return key == null ? "" : key;
    }

    private static boolean isHeadingPair(SectionedDocument source, Located s,
                                         SectionedDocument target, Located t) {
        return s.startBlock() == s.endBlock() && t.startBlock() == t.endBlock()
                && s.startBlock() >= 0 && t.startBlock() >= 0
                && source.blocks.get(s.startBlock()).isHeading()
                && target.blocks.get(t.startBlock()).isHeading();
    }

    private static boolean canRenameHeading(SectionedDocument source, Located s,
                                            SectionedDocument target, Located t) {
        CoverageBlock sb = source.blocks.get(s.startBlock());
        CoverageBlock tb = target.blocks.get(t.startBlock());
        if (!sb.parentKey.equals(tb.parentKey)) {
            return false;
        }
        if (sb.nonFact || tb.nonFact) {
            return true;
        }
        return renameContained(sb.canonicalText, tb.canonicalText);
    }

    /**
     * 受控标题重命名：只允许“一个标题是另一个标题的受控前后缀扩展”。
     *
     * <p>边界：
     * <ul>
     *   <li>较短标题至少 {@link #MIN_RENAME_LENGTH} 个 canonical 字符；</li>
     *   <li>差额不超过 {@link #MAX_RENAME_SUFFIX} 个字符；</li>
     *   <li>拒绝 ASCII 词内部的前缀关系（Java → JavaScript、项目 A → 项目 AB），
     *       避免“共享技术词/字母前缀”被当作同一项目。</li>
     * </ul>
     */
    private static boolean renameContained(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        String shorter = a.length() <= b.length() ? a : b;
        String longer = shorter.equals(a) ? b : a;
        if (shorter.length() < MIN_RENAME_LENGTH || !longer.contains(shorter)) {
            return false;
        }
        if (longer.length() - shorter.length() > MAX_RENAME_SUFFIX) {
            return false;
        }
        int index = longer.indexOf(shorter);
        int end = index + shorter.length();
        char last = shorter.charAt(shorter.length() - 1);
        if (isAsciiLetterOrDigit(last) && end < longer.length()
                && isAsciiLetterOrDigit(longer.charAt(end))) {
            return false;
        }
        return true;
    }

    private static boolean isAsciiLetterOrDigit(char ch) {
        return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9');
    }

    // ---------------------------------------------------------------- 块匹配

    private MatchResult findExact(SectionedDocument source, String sourceSection,
                                  CoverageBlock target, Set<Integer> matchedSource,
                                  List<ChangeRef> refs) {
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < source.blocks.size(); i++) {
            CoverageBlock sb = source.blocks.get(i);
            if (matchedSource.contains(i) || coveredBy(refs, i, false)) {
                continue;
            }
            if (sb.nonFact) {
                continue;
            }
            if (!sb.sectionKey.equals(sourceSection)) {
                continue;
            }
            if (!sb.projectKey.equals(target.projectKey)) {
                continue;
            }
            // 允许“正文行 → ### 标题”的纯结构升级：规范化文本完全相同即视为同一事实，
            // 不因为模型把项目行写成 Markdown 标题而判为新章节。
            if (sb.isHeading() != target.isHeading()
                    && !sb.canonicalText.equals(target.canonicalText)) {
                continue;
            }
            if (sb.canonicalText.equals(target.canonicalText)) {
                candidates.add(i);
                if (candidates.size() > MAX_EQUIVALENT_CANDIDATES) {
                    break;
                }
            }
        }
        return MatchResult.of(candidates);
    }

    private MatchResult findEquivalent(SectionedDocument source, String sourceSection,
                                       CoverageBlock target, Set<Integer> matchedSource,
                                       List<ChangeRef> refs, Set<String> allowed) {
        if (target.isHeading() || target.nonFact || !target.isReliable()
                || (!target.hasFacts() && target.contentWords.isEmpty())) {
            return MatchResult.none();
        }
        if (!targetContentSupported(target, allowed)) {
            return MatchResult.none();
        }
        List<Integer> best = new ArrayList<>();
        int bestScore = -1;
        int scanned = 0;
        for (int i = 0; i < source.blocks.size(); i++) {
            CoverageBlock sb = source.blocks.get(i);
            if (!sb.sectionKey.equals(sourceSection)) {
                continue;
            }
            if (++scanned > MAX_SCAN_PER_TARGET) {
                // 无法确认“最佳候选唯一”：保守按歧义阻断，不继续无界扫描。
                return MatchResult.ambiguous(best);
            }
            if (matchedSource.contains(i) || coveredBy(refs, i, false)) {
                continue;
            }
            if (sb.nonFact || sb.isHeading() || !sb.isReliable()) {
                continue;
            }
            if (!sb.projectKey.equals(target.projectKey)) {
                continue;
            }
            if (!factsEquivalent(sb.numericMentions, target.numericMentions)) {
                continue;
            }
            int score = overlap(sb.contentWords, target.contentWords);
            boolean factAnchored = sb.hasFacts() && target.hasFacts();
            if (score <= 0 && !factAnchored) {
                continue;
            }
            // 无事实锚点的块不能只凭“共享一个词”等价：目标文本必须包含源文本
            // （或反向包含），例如“中级软件设计师”→“中级软件设计师证书”；
            // 否则会放过“用原文已有词拼出的新职责”。
            if (!factAnchored && !sb.canonicalText.isEmpty() && !target.canonicalText.isEmpty()
                    && !sb.canonicalText.contains(target.canonicalText)
                    && !target.canonicalText.contains(sb.canonicalText)) {
                continue;
            }
            if (score > bestScore) {
                bestScore = score;
                best.clear();
                best.add(i);
            }
            else if (score == bestScore) {
                best.add(i);
            }
            if (best.size() > MAX_EQUIVALENT_CANDIDATES) {
                return MatchResult.ambiguous(best);
            }
        }
        return MatchResult.of(best);
    }

    /** 块匹配结果：唯一命中、无命中，或无法唯一定位（候选超过 1 个/扫描被截断）。 */
    private record MatchResult(int index, List<Integer> candidates, boolean ambiguous) {

        static MatchResult none() {
            return new MatchResult(-1, List.of(), false);
        }

        static MatchResult of(List<Integer> candidates) {
            if (candidates.isEmpty()) {
                return none();
            }
            if (candidates.size() == 1) {
                return new MatchResult(candidates.get(0), List.of(candidates.get(0)), false);
            }
            return ambiguous(candidates);
        }

        static MatchResult ambiguous(List<Integer> candidates) {
            return new MatchResult(-1, List.copyOf(candidates), true);
        }
    }

    private static int overlap(Set<String> a, Set<String> b) {
        int count = 0;
        for (String word : b) {
            if (a.contains(word)) {
                count++;
            }
        }
        return count;
    }

    // ---------------------------------------------------------------- 文本残差

    /**
     * 纯 no-op：规范化文本相同，且行结构也相同。
     *
     * <p>只比较规范化文本会把“一行拆成两行/两行合并为一行”误判为 no-op，
     * 因为空白与换行都被抹平；因此额外保留行边界。列表符号/空白/标点变化
     * 仍视为 no-op（Prompt 规则要求无实际改动不要写 change）。
     */
    private static boolean isNoOpChange(String before, String after) {
        return CoverageText.matchNormalize(before).equals(CoverageText.matchNormalize(after))
                && lineShape(before).equals(lineShape(after));
    }

    /** 行形状：逐行规范化（去掉行首 Markdown 符号）后保留行边界。 */
    private static String lineShape(String text) {
        if (text == null) {
            return "";
        }
        String[] lines = CoverageText.nfkc(text).split("\\R", -1);
        StringBuilder shape = new StringBuilder();
        for (String line : lines) {
            shape.append(CoverageText.matchNormalize(line)).append('|');
        }
        return shape.toString();
    }

    /**
     * change 声明的 before/after 是否覆盖了其定位区间内的全部文本差异。
     * 比较规范化残差（只保留字母/汉字/数字），因此纯标点/空白差异不算未覆盖；
     * 但新增/删除/替换的实义文本会留下来并导致不相等。
     */
    private static boolean residualTextEquivalent(SectionedDocument source, Located sourceRange,
                                                  String before, SectionedDocument target,
                                                  Located targetRange, String after) {
        String sourceText = locatedMatchText(source, sourceRange);
        String targetText = locatedMatchText(target, targetRange);
        String sourceResidual = removeOnce(sourceText, CoverageText.matchNormalize(before));
        String targetResidual = removeOnce(targetText, CoverageText.matchNormalize(after));
        if (sourceResidual == null || targetResidual == null) {
            // 理论上 locate 已确认可定位；这里返回 false 保证“无法核对”不被放行。
            return false;
        }
        return CoverageText.canonical(sourceResidual).equals(CoverageText.canonical(targetResidual));
    }

    private static String locatedMatchText(SectionedDocument document, Located located) {
        StringBuilder text = new StringBuilder();
        for (int i = located.startBlock(); i <= located.endBlock()
                && i < document.blocks.size(); i++) {
            text.append(document.blocks.get(i).matchText);
        }
        return text.toString();
    }

    private static String removeOnce(String text, String needle) {
        if (needle == null || needle.isEmpty()) {
            return null;
        }
        int index = text.indexOf(needle);
        if (index < 0) {
            return null;
        }
        return text.substring(0, index) + text.substring(index + needle.length());
    }

    // ---------------------------------------------------------------- 事实/内容

    private static List<NumericMention> unionFacts(List<CoverageBlock> blocks, Located located) {
        List<NumericMention> result = new ArrayList<>();
        for (int i = located.startBlock(); i <= located.endBlock() && i < blocks.size(); i++) {
            result.addAll(blocks.get(i).numericMentions);
        }
        return result;
    }

    /**
     * 按项目标识分组事实。项目标识在后续无标识的块上继承，
     * 这样“项目 A：”标题行与下面的正文可以被当作同一项目；
     * 遇到新的项目标识则切换。
     */
    private static Map<String, List<NumericMention>> projectGroups(List<CoverageBlock> blocks,
                                                                   Located located) {
        Map<String, List<NumericMention>> groups = new LinkedHashMap<>();
        String current = "";
        for (int i = located.startBlock(); i <= located.endBlock() && i < blocks.size(); i++) {
            CoverageBlock block = blocks.get(i);
            if (!block.projectKey.isEmpty()) {
                current = block.projectKey;
            }
            groups.computeIfAbsent(current, k -> new ArrayList<>()).addAll(block.numericMentions);
        }
        return groups;
    }

    private boolean groupsEquivalent(Map<String, List<NumericMention>> source,
                                     Map<String, List<NumericMention>> target) {
        if (!source.keySet().equals(target.keySet())) {
            return false;
        }
        for (String key : source.keySet()) {
            if (!factsEquivalent(source.get(key), target.get(key))) {
                return false;
            }
        }
        return true;
    }

    private boolean residueEquivalent(List<NumericMention> sourceFacts,
                                      List<NumericMention> beforeFacts,
                                      List<NumericMention> targetFacts,
                                      List<NumericMention> afterFacts) {
        List<NumericMention> sourceResidue = removeMatching(sourceFacts, beforeFacts);
        if (sourceResidue == null) {
            return false;
        }
        List<NumericMention> targetResidue = removeMatching(targetFacts, afterFacts);
        if (targetResidue == null) {
            return false;
        }
        return factsEquivalent(sourceResidue, targetResidue);
    }

    private List<NumericMention> removeMatching(List<NumericMention> source,
                                                List<NumericMention> remove) {
        List<NumericMention> residue = new ArrayList<>(source);
        for (NumericMention candidate : remove) {
            boolean removed = false;
            for (int i = 0; i < residue.size(); i++) {
                if (mentionEquivalent(residue.get(i), candidate)) {
                    residue.remove(i);
                    removed = true;
                    break;
                }
            }
            if (!removed) {
                return null;
            }
        }
        return residue;
    }

    private boolean factsEquivalent(List<NumericMention> first, List<NumericMention> second) {
        if (first.size() != second.size()) {
            return false;
        }
        boolean[] used = new boolean[second.size()];
        for (NumericMention left : first) {
            boolean matched = false;
            for (int i = 0; i < second.size(); i++) {
                if (!used[i] && mentionEquivalent(left, second.get(i))) {
                    used[i] = true;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private boolean mentionEquivalent(NumericMention left, NumericMention right) {
        if (left.isUnsupported() || right.isUnsupported()) {
            return left.isUnsupported() && right.isUnsupported()
                    && left.value().equals(right.value());
        }
        if (left.kind() != right.kind() || !left.value().equals(right.value())
                || !left.unit().equals(right.unit())) {
            return false;
        }
        if (left.kind() == NumericMention.Kind.DATE
                || left.kind() == NumericMention.Kind.VERSION) {
            return true;
        }
        if (!left.metricKey().equals(right.metricKey())) {
            boolean clarified = (left.metricKey().isEmpty() || right.metricKey().isEmpty())
                    && left.unitFamily() == NumericMention.UnitFamily.TIME
                    && (NumericLexicons.TIME_CLARIFICATION_METRICS.contains(left.metricKey())
                    || NumericLexicons.TIME_CLARIFICATION_METRICS.contains(right.metricKey()))
                    && !left.subjectKey().isEmpty()
                    && left.subjectKey().equals(right.subjectKey());
            if (!clarified) {
                return false;
            }
        }
        return left.objectKey().equals(right.objectKey())
                && left.subjectKey().equals(right.subjectKey())
                && left.direction() == right.direction()
                && left.qualifier() == right.qualifier()
                && left.statisticKey().equals(right.statisticKey());
    }

    private Set<String> allowedVocabulary(SectionedDocument source) {
        Set<String> allowed = new LinkedHashSet<>(source.vocabulary);
        allowed.addAll(GENERIC_WORDS);
        allowed.addAll(NumericLexicons.OBJECTS);
        allowed.addAll(NumericLexicons.SUBJECTS);
        allowed.addAll(NumericLexicons.COUNT_SUFFIXES);
        allowed.addAll(NumericLexicons.QUALIFIERS.keySet());
        allowed.addAll(NumericLexicons.STATISTICS.keySet());
        allowed.addAll(NumericLexicons.CONJUNCTIONS);
        for (var metric : NumericLexicons.METRIC_ALIASES) {
            allowed.add(metric.alias());
        }
        for (var unit : NumericLexicons.UNIT_ALIASES) {
            allowed.add(unit.alias());
        }
        for (String key : source.sectionKeys) {
            for (String part : key.split("/")) {
                if (!part.isEmpty()) {
                    allowed.add(part);
                }
            }
        }
        return allowed;
    }

    private Set<String> contentWordsWithoutFacts(String text) {
        List<NumericMention> facts = extractor.extract(text);
        if (facts.isEmpty()) {
            return CoverageText.contentWords(text);
        }
        StringBuilder residue = new StringBuilder(text);
        for (NumericMention fact : facts) {
            int start = Math.max(0, Math.min(fact.start(), residue.length()));
            int end = Math.max(start, Math.min(fact.end(), residue.length()));
            for (int i = start; i < end; i++) {
                residue.setCharAt(i, ' ');
            }
        }
        return CoverageText.contentWords(residue.toString());
    }

    private List<String> newContentWords(Set<String> words, Set<String> allowed) {
        List<String> result = new ArrayList<>();
        for (String word : words) {
            if (!CoverageText.hasCjk(word)) {
                continue;
            }
            if (!supportedWord(word, allowed, new HashSet<>())) {
                result.add(word);
            }
        }
        return result;
    }

    private boolean targetContentSupported(CoverageBlock target, Set<String> allowed) {
        for (String word : target.contentWords) {
            if (CoverageText.hasCjk(word) && !supportedWord(word, allowed, new HashSet<>())) {
                return false;
            }
        }
        return true;
    }

    private boolean supportedWord(String word, Set<String> allowed, Set<String> failed) {
        if (word.length() < 2) {
            return false;
        }
        if (allowed.contains(word)) {
            return true;
        }
        if (failed.contains(word)) {
            return false;
        }
        for (int end = word.length(); end >= 2; end--) {
            String prefix = word.substring(0, end);
            if (!allowed.contains(prefix)) {
                continue;
            }
            if (end == word.length()
                    || supportedWord(word.substring(end), allowed, failed)) {
                return true;
            }
        }
        failed.add(word);
        return false;
    }

    private String factTextForChange(String text) {
        if (text == null || text.indexOf('|') < 0) {
            return text == null ? "" : text;
        }
        String[] lines = text.split("\\R");
        List<String> header = null;
        StringBuilder result = new StringBuilder();
        for (String line : lines) {
            String trimmed = line.strip();
            if (!trimmed.contains("|")) {
                result.append(trimmed).append('\n');
                continue;
            }
            List<String> cells = tableCells(trimmed);
            if (cells.isEmpty() || isTableSeparator(cells)) {
                continue;
            }
            if (header == null) {
                header = cells;
                continue;
            }
            for (int i = 0; i < cells.size(); i++) {
                if (!cells.get(i).isEmpty() && i < header.size()) {
                    result.append(header.get(i)).append(": ").append(cells.get(i)).append('\n');
                }
            }
        }
        return result.length() == 0 ? text : result.toString();
    }

    private static List<String> tableCells(String line) {
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

    private static boolean isTableSeparator(List<String> cells) {
        for (String cell : cells) {
            if (!cell.isBlank() && !cell.matches(":?-{2,}:?")) {
                return false;
            }
        }
        return !cells.isEmpty();
    }

    // ---------------------------------------------------------------- 覆盖/区间/重复

    private static void markCovered(SectionedDocument doc, Located located, Set<Integer> covered) {
        for (int i = located.startBlock(); i <= located.endBlock() && i < doc.blocks.size(); i++) {
            covered.add(i);
        }
    }

    private static void addIgnored(SectionedDocument doc, Located located, List<TextRange> ranges) {
        for (int i = located.startBlock(); i <= located.endBlock() && i < doc.blocks.size(); i++) {
            CoverageBlock block = doc.blocks.get(i);
            ranges.add(new TextRange(block.rawStart, block.rawEnd));
        }
    }

    private static void addIgnoredPair(CoverageBlock source, CoverageBlock target,
                                       List<TextRange> ignoredSource,
                                       List<TextRange> ignoredTarget) {
        if (source.numericMentions.isEmpty() && target.numericMentions.isEmpty()) {
            return;
        }
        ignoredSource.add(new TextRange(source.rawStart, source.rawEnd));
        ignoredTarget.add(new TextRange(target.rawStart, target.rawEnd));
    }

    private static boolean coveredBy(List<ChangeRef> refs, int index, boolean target) {
        for (ChangeRef ref : refs) {
            if (target ? ref.coveredTarget.contains(index) : ref.coveredSource.contains(index)) {
                return true;
            }
        }
        return false;
    }

    private static void detectDuplicates(List<ChangeRef> refs, List<Finding> findings) {
        Map<String, ChangeRef> seen = new HashMap<>();
        for (ChangeRef ref : refs) {
            String key = CoverageText.matchNormalize(ref.beforeText) + " "
                    + CoverageText.matchNormalize(ref.afterText);
            ChangeRef first = seen.putIfAbsent(key, ref);
            if (first != null) {
                ref.duplicate = true;
                findings.add(new Finding(Code.CHANGE_DUPLICATE, -1, ref.index));
            }
        }
    }
}
