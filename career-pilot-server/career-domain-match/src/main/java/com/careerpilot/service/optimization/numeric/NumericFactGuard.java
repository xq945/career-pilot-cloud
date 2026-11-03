package com.careerpilot.service.optimization.numeric;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.service.optimization.numeric.NumericMention.Direction;
import com.careerpilot.service.optimization.numeric.NumericMention.Kind;
import com.careerpilot.service.optimization.numeric.NumericMention.Qualifier;
import com.careerpilot.service.optimization.numeric.NumericMention.UnitFamily;

/**
 * 数字事实一致性守卫（修复 B）。
 *
 * <p>采用三态：MATCH / MISMATCH / INDETERMINATE。只有可识别的严格上下文、
 * 已声明的别名、受控时间澄清，或“整行规范化签名完全相同”的局部未变，
 * 才返回 MATCH；无上下文时不会仅凭数值、单位或出现次数放行。
 * MISMATCH 与 INDETERMINATE 在本轮都阻断保存。
 */
public final class NumericFactGuard {

    public enum Verdict {
        MATCH,
        MISMATCH,
        INDETERMINATE
    }

    public enum Reason {
        MATCH_EXACT,
        MATCH_ALIAS,
        MATCH_CLARIFIED,
        MATCH_LOCAL_UNCHANGED,
        MISMATCH_NEW_VALUE,
        MISMATCH_UNIT,
        MISMATCH_METRIC,
        MISMATCH_OBJECT,
        MISMATCH_SUBJECT,
        MISMATCH_DIRECTION,
        MISMATCH_QUALIFIER,
        MISMATCH_STATISTIC,
        MISMATCH_OCCURRENCE,
        MISMATCH_CONTEXT,
        MISMATCH_UNSUPPORTED_CHANGED,
        INDETERMINATE_MISSING_CONTEXT,
        INDETERMINATE_ONE_SIDED_CONTEXT,
        INDETERMINATE_AMBIGUOUS_BINDING,
        INDETERMINATE_MULTIPLE_CANDIDATES,
        INDETERMINATE_LINE_CHANGED
    }

    public record Finding(Verdict verdict, Reason reason, Integer changeIndex,
                          int occurrence, String messageKey) {
    }

    public record Audit(int resumeMentions, int optimizedMentions, int matched,
                        int mismatched, int indeterminate, List<Finding> findings) {
    }

    private static final Pattern SHAPE_PUNCT = Pattern.compile("[^\\p{L}\\p{N}]+");

    private final NumericMentionExtractor extractor = new NumericMentionExtractor();

    public Audit check(String resumeContent, String optimizedContent, List<ChangeItem> changes) {
        return check(resumeContent, optimizedContent, changes, List.of(), List.of());
    }

    /**
     * 覆盖校验已证明事实等价的块可整体跳过数字比较（例如表格↔列表、行合并）。
     * 区间按 NFKC 后文本位置计算；未证明等价的块不受影响。
     */
    public Audit check(String resumeContent, String optimizedContent, List<ChangeItem> changes,
                       List<TextRange> ignoredResume, List<TextRange> ignoredOptimized) {
        List<NumericMention> resume = extractor.extract(resumeContent).stream()
                .filter(m -> !ignored(m.start(), ignoredResume))
                .toList();
        List<NumericMention> optimized = extractor.extract(optimizedContent).stream()
                .filter(m -> !ignored(m.start(), ignoredOptimized))
                .toList();
        List<Finding> findings = new ArrayList<>();

        int matched = matchDocuments(resume, optimized, findings);
        checkChangeSkeleton(changes, resume, findings);

        int mismatched = (int) findings.stream().filter(f -> f.verdict() == Verdict.MISMATCH).count();
        int indeterminate = (int) findings.stream()
                .filter(f -> f.verdict() == Verdict.INDETERMINATE).count();
        return new Audit(resume.size(), optimized.size(), matched, mismatched, indeterminate,
                List.copyOf(findings));
    }

    private static boolean ignored(int position, List<TextRange> ranges) {
        for (TextRange range : ranges) {
            if (range.contains(position)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 文档级匹配

    private int matchDocuments(List<NumericMention> resume, List<NumericMention> optimized,
                               List<Finding> findings) {
        int oSize = optimized.size();
        int rSize = resume.size();
        List<List<Integer>> strictEdges = new ArrayList<>(oSize);
        List<List<Integer>> localEdges = new ArrayList<>(oSize);
        List<List<Relation>> relations = new ArrayList<>(oSize);
        for (int o = 0; o < oSize; o++) {
            strictEdges.add(new ArrayList<>());
            localEdges.add(new ArrayList<>());
            relations.add(new ArrayList<>());
        }
        for (int o = 0; o < oSize; o++) {
            NumericMention om = optimized.get(o);
            for (int r = 0; r < rSize; r++) {
                NumericMention rm = resume.get(r);
                Relation relation = relation(om, rm);
                relations.get(o).add(relation);
                if (relation == null) {
                    continue;
                }
                if (relation.verdict == Verdict.MATCH) {
                    strictEdges.get(o).add(r);
                }
                else if (sameKindValueUnit(om, rm)
                        && om.lineSignature().equals(rm.lineSignature())
                        && om.contextSignature().equals(rm.contextSignature())) {
                    localEdges.get(o).add(r);
                }
            }
        }

        int[] rightOwner = new int[rSize];
        Arrays.fill(rightOwner, -1);
        boolean[] matchedO = new boolean[oSize];
        for (int o = 0; o < oSize; o++) {
            boolean[] seen = new boolean[rSize];
            if (augment(o, strictEdges, rightOwner, seen)) {
                matchedO[o] = true;
            }
        }
        for (int o = 0; o < oSize; o++) {
            if (matchedO[o]) {
                continue;
            }
            boolean[] seen = new boolean[rSize];
            if (augment(o, localEdges, rightOwner, seen)) {
                matchedO[o] = true;
            }
        }

        int matched = 0;
        for (boolean value : matchedO) {
            if (value) {
                matched++;
            }
        }

        for (int o = 0; o < oSize; o++) {
            if (matchedO[o]) {
                continue;
            }
            Finding finding = classifyUnmatched(optimized.get(o), relations.get(o), resume,
                    rightOwner, o + 1);
            findings.add(finding);
        }
        return matched;
    }

    private boolean augment(int o, List<List<Integer>> edges, int[] rightOwner, boolean[] seen) {
        for (int r : edges.get(o)) {
            if (seen[r]) {
                continue;
            }
            seen[r] = true;
            if (rightOwner[r] == -1 || augment(rightOwner[r], edges, rightOwner, seen)) {
                rightOwner[r] = o;
                return true;
            }
        }
        return false;
    }

    private Finding classifyUnmatched(NumericMention om, List<Relation> relations,
                                      List<NumericMention> resume, int[] rightOwner,
                                      int occurrence) {
        boolean anySameValue = false;
        boolean anySameUnit = false;
        boolean anyUnmatchedSameUnit = false;
        Reason conflict = null;
        Reason indeterminate = null;
        for (int r = 0; r < resume.size(); r++) {
            NumericMention rm = resume.get(r);
            if (!sameValue(om, rm)) {
                continue;
            }
            anySameValue = true;
            if (!sameUnit(om, rm)) {
                continue;
            }
            anySameUnit = true;
            Relation relation = relations.get(r);
            if (relation == null) {
                continue;
            }
            if (relation.verdict == Verdict.MISMATCH && conflict == null) {
                conflict = relation.reason;
            }
            if (relation.verdict == Verdict.INDETERMINATE && indeterminate == null) {
                indeterminate = relation.reason;
            }
            if (rightOwner[r] == -1) {
                anyUnmatchedSameUnit = true;
            }
        }

        if (!anySameValue) {
            if (om.isUnsupported()) {
                return finding(Verdict.MISMATCH, Reason.MISMATCH_UNSUPPORTED_CHANGED, occurrence);
            }
            return finding(Verdict.MISMATCH, Reason.MISMATCH_NEW_VALUE, occurrence);
        }
        if (om.isUnsupported()) {
            if (anySameUnit && anyUnmatchedSameUnit && indeterminate != null) {
                return finding(Verdict.INDETERMINATE, Reason.INDETERMINATE_MISSING_CONTEXT,
                        occurrence);
            }
            if (anySameUnit && !anyUnmatchedSameUnit) {
                return finding(Verdict.MISMATCH, Reason.MISMATCH_OCCURRENCE, occurrence);
            }
            return finding(Verdict.MISMATCH, Reason.MISMATCH_UNSUPPORTED_CHANGED, occurrence);
        }
        if (!anySameUnit) {
            return finding(Verdict.MISMATCH, Reason.MISMATCH_UNIT, occurrence);
        }
        if (conflict != null) {
            return finding(Verdict.MISMATCH, conflict, occurrence);
        }
        if (indeterminate != null) {
            return finding(Verdict.INDETERMINATE, indeterminate, occurrence);
        }
        if (!anyUnmatchedSameUnit) {
            return finding(Verdict.MISMATCH, Reason.MISMATCH_OCCURRENCE, occurrence);
        }
        return finding(Verdict.INDETERMINATE, Reason.INDETERMINATE_MISSING_CONTEXT, occurrence);
    }

    private Finding finding(Verdict verdict, Reason reason, int occurrence) {
        return new Finding(verdict, reason, null, occurrence, reason.name());
    }

    // ---------------------------------------------------------------- 关系判定

    private Relation relation(NumericMention o, NumericMention r) {
        if (!sameKindValueUnit(o, r)) {
            return null;
        }
        // 上下文闸门：项目/表格表头/行内标签不同或单侧缺失时，值相同也不能算匹配。
        // 该检查先于 DATE/VERSION/UNSUPPORTED 等全部 MATCH 分支。
        if (!o.contextSignature().equals(r.contextSignature())) {
            return new Relation(Verdict.MISMATCH, Reason.MISMATCH_CONTEXT);
        }
        if (o.kind() == Kind.DATE) {
            return new Relation(Verdict.MATCH, Reason.MATCH_EXACT);
        }
        if (o.kind() == Kind.VERSION) {
            return versionRelation(o, r);
        }
        if (o.isUnsupported() || r.isUnsupported()) {
            if (o.lineSignature().equals(r.lineSignature())) {
                return new Relation(Verdict.MATCH, Reason.MATCH_LOCAL_UNCHANGED);
            }
            return new Relation(Verdict.INDETERMINATE, Reason.INDETERMINATE_MISSING_CONTEXT);
        }
        if (o.ambiguousBinding() || r.ambiguousBinding()) {
            if (o.lineSignature().equals(r.lineSignature())) {
                return new Relation(Verdict.MATCH, Reason.MATCH_LOCAL_UNCHANGED);
            }
            return new Relation(Verdict.INDETERMINATE, Reason.INDETERMINATE_AMBIGUOUS_BINDING);
        }

        boolean anchor = false;
        boolean oneSided = false;

        // 指标：同组直接一致；优化稿补充时间类通用指标且有相同主体，属于受控澄清
        if (!o.metricKey().isEmpty() && !r.metricKey().isEmpty()) {
            if (!o.metricKey().equals(r.metricKey())) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_METRIC);
            }
            anchor = true;
        }
        else if (!o.metricKey().isEmpty() || !r.metricKey().isEmpty()) {
            boolean clarified = !o.metricKey().isEmpty()
                    && r.metricKey().isEmpty()
                    && o.unitFamily() == UnitFamily.TIME
                    && NumericLexicons.TIME_CLARIFICATION_METRICS.contains(o.metricKey())
                    && !o.subjectKey().isEmpty()
                    && !r.subjectKey().isEmpty()
                    && o.subjectKey().equals(r.subjectKey());
            if (!clarified) {
                oneSided = true;
            }
        }

        // 对象
        if (!o.objectKey().isEmpty() && !r.objectKey().isEmpty()) {
            if (!o.objectKey().equals(r.objectKey())) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_OBJECT);
            }
            anchor = true;
        }
        else if (!o.objectKey().isEmpty() || !r.objectKey().isEmpty()) {
            oneSided = true;
        }

        // 主体
        if (!o.subjectKey().isEmpty() && !r.subjectKey().isEmpty()) {
            if (!o.subjectKey().equals(r.subjectKey())) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_SUBJECT);
            }
            anchor = true;
        }
        else if (!o.subjectKey().isEmpty() || !r.subjectKey().isEmpty()) {
            oneSided = true;
        }

        // 方向：NEUTRAL（优化/改善/调整）是通配，不单独构成事实方向
        if (o.direction() != Direction.NONE && r.direction() != Direction.NONE) {
            if (o.direction() != r.direction()
                    && o.direction() != Direction.NEUTRAL
                    && r.direction() != Direction.NEUTRAL) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_DIRECTION);
            }
        }
        else if (o.direction() != Direction.NONE || r.direction() != Direction.NONE) {
            Direction present = o.direction() != Direction.NONE ? o.direction() : r.direction();
            if (present != Direction.NEUTRAL) {
                oneSided = true;
            }
        }

        // 限定词：任何一侧变化都是明确的事实强度变化
        if (o.qualifier() != Qualifier.NONE && r.qualifier() != Qualifier.NONE) {
            if (o.qualifier() != r.qualifier()) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_QUALIFIER);
            }
        }
        else if (o.qualifier() != Qualifier.NONE || r.qualifier() != Qualifier.NONE) {
            return new Relation(Verdict.MISMATCH, Reason.MISMATCH_QUALIFIER);
        }

        // 统计口径
        if (!o.statisticKey().isEmpty() && !r.statisticKey().isEmpty()) {
            if (!o.statisticKey().equals(r.statisticKey())) {
                return new Relation(Verdict.MISMATCH, Reason.MISMATCH_STATISTIC);
            }
        }
        else if (!o.statisticKey().isEmpty() || !r.statisticKey().isEmpty()) {
            oneSided = true;
        }

        if (oneSided) {
            return new Relation(Verdict.INDETERMINATE, Reason.INDETERMINATE_ONE_SIDED_CONTEXT);
        }
        if (anchor) {
            return new Relation(Verdict.MATCH, Reason.MATCH_EXACT);
        }
        if (o.lineSignature().equals(r.lineSignature())) {
            return new Relation(Verdict.MATCH, Reason.MATCH_LOCAL_UNCHANGED);
        }
        return new Relation(Verdict.INDETERMINATE, Reason.INDETERMINATE_LINE_CHANGED);
    }

    private Relation versionRelation(NumericMention o, NumericMention r) {
        if (o.subjectKey().isEmpty() || r.subjectKey().isEmpty()) {
            if (o.lineSignature().equals(r.lineSignature())) {
                return new Relation(Verdict.MATCH, Reason.MATCH_LOCAL_UNCHANGED);
            }
            return new Relation(Verdict.INDETERMINATE, Reason.INDETERMINATE_MISSING_CONTEXT);
        }
        if (anchorsIntersect(o.subjectKey(), r.subjectKey())) {
            return new Relation(Verdict.MATCH, Reason.MATCH_EXACT);
        }
        return new Relation(Verdict.MISMATCH, Reason.MISMATCH_SUBJECT);
    }

    private boolean anchorsIntersect(String first, String second) {
        List<String> left = Arrays.asList(first.split("\\|"));
        List<String> right = Arrays.asList(second.split("\\|"));
        for (String a : left) {
            for (String b : right) {
                if (!a.isEmpty() && a.equals(b)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sameKindValueUnit(NumericMention o, NumericMention r) {
        return o.kind() == r.kind() && sameValue(o, r) && sameUnit(o, r);
    }

    private boolean sameValue(NumericMention o, NumericMention r) {
        return o.value().equals(r.value());
    }

    private boolean sameUnit(NumericMention o, NumericMention r) {
        return o.unit().equals(r.unit());
    }

    private record Relation(Verdict verdict, Reason reason) {
    }

    // ---------------------------------------------------------------- change 局部骨架

    private void checkChangeSkeleton(List<ChangeItem> changes, List<NumericMention> resume,
                                     List<Finding> findings) {
        if (changes == null) {
            return;
        }
        for (int i = 0; i < changes.size(); i++) {
            ChangeItem item = changes.get(i);
            if (item == null || item.getBefore() == null || item.getAfter() == null
                    || item.getBefore().isBlank() || item.getAfter().isBlank()) {
                continue;
            }
            List<NumericMention> before = extractor.extract(item.getBefore());
            List<NumericMention> after = extractor.extract(item.getAfter());
            if (before.isEmpty() && after.isEmpty()) {
                continue;
            }
            String beforeShape = shape(item.getBefore(), before);
            String afterShape = shape(item.getAfter(), after);
            if (!beforeShape.equals(afterShape) || before.size() != after.size()) {
                continue;
            }
            for (int m = 0; m < before.size(); m++) {
                NumericMention b = before.get(m);
                NumericMention a = after.get(m);
                if (numericTokenEquals(b, a)) {
                    continue;
                }
                Reason reason;
                if (b.isUnsupported() || a.isUnsupported()) {
                    reason = Reason.MISMATCH_UNSUPPORTED_CHANGED;
                }
                else if (b.kind() == a.kind() && b.value().equals(a.value())
                        && !b.unit().equals(a.unit())) {
                    reason = Reason.MISMATCH_UNIT;
                }
                else if (b.kind() != a.kind() || !b.value().equals(a.value())) {
                    reason = existsValue(resume, a.value())
                            ? Reason.MISMATCH_CONTEXT : Reason.MISMATCH_NEW_VALUE;
                }
                else {
                    reason = Reason.MISMATCH_UNIT;
                }
                findings.add(new Finding(Verdict.MISMATCH, reason, i, m + 1,
                        reason.name()));
            }
        }
    }

    private boolean numericTokenEquals(NumericMention a, NumericMention b) {
        return a.kind() == b.kind() && a.value().equals(b.value()) && a.unit().equals(b.unit());
    }

    private boolean existsValue(List<NumericMention> resume, String value) {
        return resume.stream().anyMatch(m -> m.value().equals(value));
    }

    private String shape(String text, List<NumericMention> mentions) {
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        for (NumericMention mention : mentions) {
            if (mention.start() < cursor || mention.end() > text.length()) {
                continue;
            }
            result.append(squash(text.substring(cursor, mention.start()))).append('#');
            cursor = mention.end();
        }
        result.append(squash(text.substring(Math.min(cursor, text.length()))));
        return result.toString();
    }

    private String squash(String text) {
        return SHAPE_PUNCT.matcher(text.toLowerCase(Locale.ROOT)).replaceAll("");
    }
}
