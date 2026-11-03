package com.careerpilot.service.optimization.coverage;

/**
 * 块级事实 token（修复 C，阶段 1）。
 *
 * <p>token 只描述“这个块携带了哪类可核对的事实”，不证明事实为真。它与
 * {@link com.careerpilot.service.optimization.numeric.NumericMention} 的关系是：
 * 数字/日期直接适配自后者（不另造解析器），专名/技术词/职责短语使用与
 * {@code ResumeOptimizationFactChecker} 相同的受控规则，项目 token 来自块的项目标识。
 *
 * <p>位置 {@code start}/{@code end} 以块的 {@code factText} 为基准；项目 token 没有
 * 连续文本位置，固定为 {@code (-1, -1)}。本类型不输出正文。
 */
record FactToken(Kind kind, String value, int start, int end) {

    enum Kind {
        /** 业务数字（计数、比例、时长、金额等），含暂不支持校验的数字表达 */
        NUMBER,
        /** 日期或日期区间 */
        DATE,
        /** 机构/竞赛/证书专名 */
        PROPER_NOUN,
        /** 英文技术词（受控停用词之外） */
        TECH_TERM,
        /** 职责/成果升级短语 */
        OWNERSHIP,
        /** 块内可识别的项目身份 */
        PROJECT
    }
}
