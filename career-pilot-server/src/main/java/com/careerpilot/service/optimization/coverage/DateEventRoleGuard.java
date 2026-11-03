package com.careerpilot.service.optimization.coverage;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.careerpilot.service.optimization.coverage.RoleLexicon.Role;
import com.careerpilot.service.optimization.numeric.NumericMention;

/**
 * 日期 + 事件角色 + 主体 + 章节绑定（修复 C）。
 *
 * <p>只补充数字守卫看不到的语义：同一日期值在原文/优化稿中表示的事件不同
 * （入职→离职、开始→结束、毕业→入学、获得→报名），或日期区间顺序倒置。
 * 不重复比较日期数字本身；值不同的日期交给 {@code NumericFactGuard}。
 *
 * <p>绑定规则：
 * <ul>
 *   <li>角色词必须在日期所在<strong>同一分句</strong>内（以句号/分号/问号/叹号/换行分隔），
 *       不跨句；中文逗号不算强边界；</li>
 *   <li>角色与日期的距离不超过 {@link RoleLexicon#MAX_ROLE_DISTANCE}，超出视为未知；</li>
 *   <li>同距离出现不同角色 → {@link Code#CONTEXT_AMBIGUOUS}，不猜；</li>
 *   <li>主体只在日期同一分句内提取；一侧有主体、另一侧没有 → 歧义；</li>
 *   <li>词表外的动词保持 UNKNOWN，不映射成已知角色。</li>
 * </ul>
 */
public final class DateEventRoleGuard {

    public enum Code {
        ROLE_MISMATCH,
        CONTEXT_AMBIGUOUS,
        SUBJECT_MISMATCH,
        SECTION_MISMATCH,
        RANGE_ORDER_INVALID
    }

    public record Finding(Code code, int targetBlock, Integer sourceBlock) {
    }

    public record Audit(List<Finding> findings) {
    }

    /** 同章节同值日期有多个候选：不猜，按上下文歧义处理。 */
    private static final int AMBIGUOUS = -2;

    private static final Pattern SUBJECT = Pattern.compile(
            "([A-Za-z0-9\\u4e00-\\u9fa5]{1,16})\\s*(公司|大学|学院|学校|研究院|实验室)");

    private static final String STRONG_BOUNDARIES = "。；;！？!?\n";

    private static final List<RoleAlias> ROLE_ALIASES = buildRoleAliases();

    private final DocumentParser parser = new DocumentParser();

    public Audit check(String resumeContent, String optimizedContent,
                       Map<String, String> targetToSource) {
        SectionedDocument source = parser.parse(resumeContent);
        SectionedDocument target = parser.parse(optimizedContent);
        if (!source.complete || !target.complete) {
            // 覆盖守卫已按 UNPARSABLE 阻断；日期守卫不再基于截断结果做语义推断。
            return new Audit(List.of());
        }

        List<DateRef> sourceDates = new ArrayList<>();
        for (CoverageBlock block : source.blocks) {
            for (NumericMention mention : block.numericMentions) {
                if (mention.kind() == NumericMention.Kind.DATE && !mention.isUnsupported()) {
                    sourceDates.add(new DateRef(block, mention));
                }
            }
        }
        boolean[] used = new boolean[sourceDates.size()];
        List<Finding> findings = new ArrayList<>();

        for (CoverageBlock targetBlock : target.blocks) {
            // 摘要型新增章节由覆盖守卫做 token 级校验；这里不按章节搬移处理。
            if (DocumentCoverageGuard.isSummarySection(targetBlock.sectionKey)) {
                continue;
            }
            for (NumericMention targetDate : targetBlock.numericMentions) {
                if (targetDate.kind() != NumericMention.Kind.DATE || targetDate.isUnsupported()) {
                    continue;
                }
                String logicalSection = targetToSource.getOrDefault(
                        targetBlock.sectionKey, targetBlock.sectionKey);
                int sourceIndex = findSourceDate(sourceDates, used, logicalSection,
                        targetDate.value());
                if (sourceIndex == AMBIGUOUS) {
                    findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                            indexOf(target, targetBlock), null));
                    continue;
                }
                boolean otherSection = false;
                if (sourceIndex < 0) {
                    sourceIndex = findReversedRange(sourceDates, used, logicalSection,
                            targetDate.value());
                    if (sourceIndex == AMBIGUOUS) {
                        findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                                indexOf(target, targetBlock), null));
                        continue;
                    }
                    if (sourceIndex >= 0) {
                        used[sourceIndex] = true;
                        findings.add(new Finding(Code.RANGE_ORDER_INVALID,
                                indexOf(target, targetBlock),
                                indexOf(source, sourceDates.get(sourceIndex).block())));
                        continue;
                    }
                }
                if (sourceIndex < 0) {
                    sourceIndex = findSourceDateAnySection(sourceDates, used,
                            targetDate.value());
                    if (sourceIndex == AMBIGUOUS) {
                        findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                                indexOf(target, targetBlock), null));
                        continue;
                    }
                    otherSection = sourceIndex >= 0;
                }
                if (sourceIndex < 0) {
                    continue;
                }
                used[sourceIndex] = true;
                DateRef sourceRef = sourceDates.get(sourceIndex);
                if (otherSection) {
                    findings.add(new Finding(Code.SECTION_MISMATCH,
                            indexOf(target, targetBlock), indexOf(source, sourceRef.block())));
                    continue;
                }

                if (rangeInvalid(targetDate.value())
                        && !targetDate.value().equals(sourceRef.mention().value())) {
                    findings.add(new Finding(Code.RANGE_ORDER_INVALID,
                            indexOf(target, targetBlock), indexOf(source, sourceRef.block())));
                    continue;
                }

                RoleMatch sourceRole = roleOf(sourceRef.block(), sourceRef.mention());
                RoleMatch targetRole = roleOf(targetBlock, targetDate);
                String sourceSubject = subjectOf(sourceRef.block(), sourceRef.mention());
                String targetSubject = subjectOf(targetBlock, targetDate);

                // 两侧“最近角色集合”完全相同时，事件上下文没有变化——
                // 即使存在同距离多角色（如“实习 2026.09 开始”），也不应凭空判歧义。
                if (!sourceRole.roles().equals(targetRole.roles())) {
                    if (sourceRole.roles().isEmpty() || targetRole.roles().isEmpty()) {
                        findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                                indexOf(target, targetBlock),
                                indexOf(source, sourceRef.block())));
                        continue;
                    }
                    if (sourceRole.unique() && targetRole.unique()) {
                        findings.add(new Finding(Code.ROLE_MISMATCH,
                                indexOf(target, targetBlock),
                                indexOf(source, sourceRef.block())));
                        continue;
                    }
                    findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                            indexOf(target, targetBlock), indexOf(source, sourceRef.block())));
                    continue;
                }

                if (!sourceSubject.isEmpty() && !targetSubject.isEmpty()) {
                    if (!sourceSubject.equals(targetSubject)) {
                        findings.add(new Finding(Code.SUBJECT_MISMATCH,
                                indexOf(target, targetBlock),
                                indexOf(source, sourceRef.block())));
                    }
                }
                else if (!sourceSubject.isEmpty() || !targetSubject.isEmpty()) {
                    findings.add(new Finding(Code.CONTEXT_AMBIGUOUS,
                            indexOf(target, targetBlock), indexOf(source, sourceRef.block())));
                }
            }
        }
        return new Audit(List.copyOf(findings));
    }

    private static int findSourceDate(List<DateRef> dates, boolean[] used,
                                      String section, String value) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            DateRef ref = dates.get(i);
            if (!used[i] && ref.block().sectionKey.equals(section)
                    && ref.mention().value().equals(value)) {
                matches.add(i);
            }
        }
        return uniqueOrAmbiguous(matches);
    }

    /** 源/目标区间端点相同但顺序相反（2025-03..2025-06 vs 2025-06..2025-03）。 */
    private static int findReversedRange(List<DateRef> dates, boolean[] used,
                                         String section, String value) {
        int separator = value.indexOf("..");
        if (separator <= 0) {
            return -1;
        }
        String targetStart = value.substring(0, separator);
        String targetEnd = value.substring(separator + 2);
        if (targetEnd.startsWith("OPEN")) {
            return -1;
        }
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            DateRef ref = dates.get(i);
            if (used[i] || !ref.block().sectionKey.equals(section)) {
                continue;
            }
            String sourceValue = ref.mention().value();
            int sourceSeparator = sourceValue.indexOf("..");
            if (sourceSeparator <= 0) {
                continue;
            }
            String sourceStart = sourceValue.substring(0, sourceSeparator);
            String sourceEnd = sourceValue.substring(sourceSeparator + 2);
            if (sourceStart.equals(targetEnd) && sourceEnd.equals(targetStart)) {
                matches.add(i);
            }
        }
        return uniqueOrAmbiguous(matches);
    }

    private static int findSourceDateAnySection(List<DateRef> dates, boolean[] used,
                                                String value) {
        List<Integer> matches = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            if (!used[i] && dates.get(i).mention().value().equals(value)) {
                matches.add(i);
            }
        }
        return uniqueOrAmbiguous(matches);
    }

    /** 0 个 → -1；1 个 → 下标；多个同值候选 → {@link #AMBIGUOUS}（不任选）。 */
    private static int uniqueOrAmbiguous(List<Integer> matches) {
        if (matches.isEmpty()) {
            return -1;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        return AMBIGUOUS;
    }

    private RoleMatch roleOf(CoverageBlock block, NumericMention date) {
        String text = block.factText;
        int dateStart = clamp(date.start(), text.length());
        int dateEnd = clamp(date.end(), text.length());
        int clauseStart = clauseStart(text, dateStart);
        int clauseEnd = clauseEnd(text, dateEnd);
        int bestDistance = Integer.MAX_VALUE;
        Set<Role> nearest = new LinkedHashSet<>();
        for (RoleAlias alias : ROLE_ALIASES) {
            for (int[] occurrence : occurrences(text, clauseStart, clauseEnd, alias)) {
                int distance = distance(occurrence[0], occurrence[1], dateStart, dateEnd);
                if (distance > RoleLexicon.MAX_ROLE_DISTANCE) {
                    continue;
                }
                if (distance < bestDistance) {
                    bestDistance = distance;
                    nearest.clear();
                    nearest.add(alias.role());
                }
                else if (distance == bestDistance) {
                    nearest.add(alias.role());
                }
            }
        }
        return new RoleMatch(Set.copyOf(nearest));
    }

    private String subjectOf(CoverageBlock block, NumericMention date) {
        String text = block.factText;
        int dateStart = clamp(date.start(), text.length());
        int clauseStart = clauseStart(text, dateStart);
        int clauseEnd = clauseEnd(text, clamp(date.end(), text.length()));
        String clause = text.substring(clauseStart, clauseEnd);
        int relative = dateStart - clauseStart;

        Matcher matcher = SUBJECT.matcher(clause);
        String best = "";
        int bestDistance = Integer.MAX_VALUE;
        while (matcher.find()) {
            String prefix = matcher.group(1);
            for (RoleAlias alias : ROLE_ALIASES) {
                if (!alias.ascii()) {
                    prefix = prefix.replace(alias.text(), "");
                }
            }
            prefix = prefix.replaceAll("[0-9年月日号届\\s:：,，在于到了]", "");
            if (prefix.isEmpty()) {
                continue;
            }
            int distance = Math.abs(matcher.start() - relative);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = CoverageText.canonical(prefix + matcher.group(2));
            }
        }
        return best;
    }

    // ---------------------------------------------------------------- 分句/别名

    private static int clauseStart(String text, int dateStart) {
        for (int i = dateStart - 1; i >= 0; i--) {
            if (isStrongBoundary(text, i)) {
                return i + 1;
            }
        }
        return 0;
    }

    private static int clauseEnd(String text, int dateEnd) {
        for (int i = dateEnd; i < text.length(); i++) {
            if (isStrongBoundary(text, i)) {
                return i;
            }
        }
        return text.length();
    }

    /**
     * 强边界：中文句号/分号/问号/叹号/换行；英文句点只在后面是空白或行尾时算边界，
     * 避免把日期内部的 “2025.03” 拆开（日期起点开始时不会向左扫进日期）。
     */
    private static boolean isStrongBoundary(String text, int index) {
        char ch = text.charAt(index);
        if (STRONG_BOUNDARIES.indexOf(ch) >= 0) {
            return true;
        }
        if (ch == '.') {
            int next = index + 1;
            return next >= text.length() || Character.isWhitespace(text.charAt(next));
        }
        return false;
    }

    private static List<int[]> occurrences(String text, int from, int to, RoleAlias alias) {
        List<int[]> result = new ArrayList<>();
        if (from >= to) {
            return result;
        }
        String clause = text.substring(from, to);
        if (alias.ascii()) {
            Matcher matcher = alias.pattern().matcher(clause);
            while (matcher.find()) {
                result.add(new int[]{from + matcher.start(), from + matcher.end()});
            }
            return result;
        }
        int index = clause.indexOf(alias.text());
        while (index >= 0) {
            result.add(new int[]{from + index, from + index + alias.text().length()});
            index = clause.indexOf(alias.text(), index + 1);
        }
        return result;
    }

    private static int distance(int aliasStart, int aliasEnd, int dateStart, int dateEnd) {
        if (aliasEnd <= dateStart) {
            return dateStart - aliasEnd;
        }
        if (aliasStart >= dateEnd) {
            return aliasStart - dateEnd;
        }
        return 0;
    }

    private static int clamp(int value, int length) {
        return Math.max(0, Math.min(value, length));
    }

    // ---------------------------------------------------------------- 区间顺序

    private static boolean rangeInvalid(String value) {
        int separator = value.indexOf("..");
        if (separator <= 0) {
            return false;
        }
        String start = value.substring(0, separator);
        String end = value.substring(separator + 2);
        if (end.startsWith("OPEN")) {
            return false;
        }
        return compareDateValues(start, end) > 0;
    }

    /**
     * 按年月日逐段比较，而不是整串字典序；无法解析的值返回 0（交给数字守卫，不在此处抛错）。
     */
    private static int compareDateValues(String first, String second) {
        int[] a = dateParts(first);
        int[] b = dateParts(second);
        if (a == null || b == null) {
            return 0;
        }
        for (int i = 0; i < a.length; i++) {
            if (a[i] != b[i]) {
                return Integer.compare(a[i], b[i]);
            }
        }
        return 0;
    }

    private static int[] dateParts(String value) {
        String[] parts = value.split("-");
        if (parts.length < 1 || parts.length > 3) {
            return null;
        }
        int[] result = new int[3];
        try {
            result[0] = Integer.parseInt(parts[0]);
            result[1] = parts.length >= 2 ? Integer.parseInt(parts[1]) : 0;
            result[2] = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
        }
        catch (NumberFormatException exception) {
            return null;
        }
        return result;
    }

    private static int indexOf(SectionedDocument document, CoverageBlock block) {
        return document.blocks.indexOf(block);
    }

    private static List<RoleAlias> buildRoleAliases() {
        List<RoleAlias> result = new ArrayList<>();
        for (RoleLexicon.Alias alias : RoleLexicon.aliases()) {
            Pattern pattern = alias.ascii()
                    ? Pattern.compile("(?i)(?<![A-Za-z])" + Pattern.quote(alias.text())
                            + "(?![A-Za-z])")
                    : null;
            result.add(new RoleAlias(alias.text(), alias.role(), alias.ascii(), pattern));
        }
        return List.copyOf(result);
    }

    private record DateRef(CoverageBlock block, NumericMention mention) {
    }

    /** 与日期距离最小的全部角色；空集合表示 UNKNOWN，不猜测。 */
    private record RoleMatch(Set<Role> roles) {

        boolean unique() {
            return roles.size() == 1;
        }
    }

    private record RoleAlias(String text, Role role, boolean ascii, Pattern pattern) {
    }
}
