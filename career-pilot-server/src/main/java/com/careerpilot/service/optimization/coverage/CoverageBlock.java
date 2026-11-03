package com.careerpilot.service.optimization.coverage;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.careerpilot.service.optimization.numeric.NumericMention;

/**
 * 覆盖校验的最小文档块（修复 C）。
 *
 * <p>块保留原始文本、定位文本、格式无关文本、事实 token 与内容词。
 * 数字/日期事实复用 Repair B 的 {@link NumericMention}，不另造数字解析器；
 * 专名/技术词/职责短语由 {@link FactTokenExtractor} 按与顶层事实校验相同的口径提取。
 *
 * <p>解析状态是显式的：
 * <ul>
 *   <li>{@link ParseStatus#PARSED}：结构可识别；</li>
 *   <li>{@link ParseStatus#PARTIAL}：结构可疑（如表格列数不一致、缺少分隔行），
 *       只允许“两侧规范化后完全相同”的匹配，不做等价匹配；</li>
 *   <li>{@link ParseStatus#UNPARSABLE}：无法可靠解释，发生变化时按
 *       {@code COVERAGE_UNPARSABLE} 阻断。</li>
 * </ul>
 */
final class CoverageBlock {

    enum Type {
        HEADING,
        /** 行内项目标签（项目：名称），保留项目身份但不等同于章节 */
        PROJECT_LABEL,
        LIST_ITEM,
        PARAGRAPH,
        TABLE_HEADER,
        TABLE_SEPARATOR,
        TABLE_ROW,
        CONTACT,
        GENERIC
    }

    enum ParseStatus {
        PARSED,
        PARTIAL,
        UNPARSABLE
    }

    final Type type;
    final String rawText;
    final String matchText;
    final String canonicalText;
    final String factText;
    final String sectionKey;
    final String parentKey;
    /** 行内项目标识（项目 A / 项目：名称）；空表示没有可识别的项目上下文 */
    final String projectKey;
    final int rawStart;
    final int rawEnd;
    /** 联系方式/通用标题/表格分隔等不参与事实覆盖的块 */
    final boolean nonFact;
    final List<NumericMention> numericMentions;
    final Set<String> contentWords;
    /** 块级事实 token（数字/日期/专名/技术词/职责短语/项目） */
    final List<FactToken> factTokens;
    /** 表格数据行的“列标签 → 值”；非表格行为空 */
    final Map<String, String> columns;
    final ParseStatus parseStatus;

    CoverageBlock(Type type, String rawText, String matchText, String canonicalText,
                  String factText, String sectionKey, String parentKey, String projectKey,
                  int rawStart, int rawEnd, boolean nonFact,
                  List<NumericMention> numericMentions, Set<String> contentWords,
                  List<FactToken> factTokens, Map<String, String> columns,
                  ParseStatus parseStatus) {
        this.type = type;
        this.rawText = rawText;
        this.matchText = matchText;
        this.canonicalText = canonicalText;
        this.factText = factText;
        this.sectionKey = sectionKey;
        this.parentKey = parentKey;
        this.projectKey = projectKey;
        this.rawStart = rawStart;
        this.rawEnd = rawEnd;
        this.nonFact = nonFact;
        this.numericMentions = numericMentions;
        this.contentWords = contentWords;
        this.factTokens = factTokens;
        this.columns = columns;
        this.parseStatus = parseStatus;
    }

    boolean hasFacts() {
        return !numericMentions.isEmpty();
    }

    boolean hasFactTokens() {
        return !factTokens.isEmpty();
    }

    boolean isReliable() {
        return parseStatus == ParseStatus.PARSED;
    }

    boolean isHeading() {
        return type == Type.HEADING;
    }

    boolean contains(int position) {
        return position >= rawStart && position < rawEnd;
    }
}
