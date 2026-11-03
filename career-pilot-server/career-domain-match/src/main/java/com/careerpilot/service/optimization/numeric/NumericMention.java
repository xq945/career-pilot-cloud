package com.careerpilot.service.optimization.numeric;

/**
 * 一个可解释的数字提及（修复 B）。
 *
 * <p>数字不再以 {@code Set<String>} 形式比较；每条提及保留规范化数值、单位、
 * 指标/对象/主体、方向、限定词、统计口径以及所在行的规范化签名。
 * {@code lineSignature} 只用于“局部没有可观察的数字变化”的保守匹配，
 * 不用于证明语义事实；{@code contextSignature} 记录跨行标题、表格表头与
 * 行内项目/短标签，两侧上下文不一致时保守阻断。
 *
 * <p>字段约定：未知/不适用的上下文使用空字符串或 {@link Direction#NONE}、
 * {@link Qualifier#NONE}，不使用 null。
 */
public record NumericMention(
        Kind kind,
        String value,
        String unit,
        UnitFamily unitFamily,
        String metricKey,
        String objectKey,
        String subjectKey,
        Direction direction,
        Qualifier qualifier,
        String statisticKey,
        int start,
        int end,
        String lineSignature,
        String contextSignature,
        boolean ambiguousBinding,
        String unsupportedRaw,
        boolean supported) {

    public enum Kind {
        COUNT,
        PERCENT,
        DURATION,
        MONEY,
        SIZE,
        RATE,
        DATE,
        VERSION,
        PLAIN,
        UNSUPPORTED
    }

    public enum UnitFamily {
        COUNT,
        TIME,
        MONEY,
        PERCENT,
        SIZE,
        RATE,
        DATE,
        VERSION,
        PLAIN,
        UNSUPPORTED
    }

    public enum Direction {
        NONE,
        UP,
        DOWN,
        NEUTRAL
    }

    public enum Qualifier {
        NONE,
        APPROX,
        ABOVE,
        BELOW,
        AT_LEAST,
        AT_MOST,
        TARGET
    }

    /** 是否有可用于严格匹配的具体锚点（指标/对象/主体之一） */
    public boolean hasSpecificAnchor() {
        return !metricKey.isEmpty() || !objectKey.isEmpty() || !subjectKey.isEmpty();
    }

    public boolean isDateOrVersion() {
        return kind == Kind.DATE || kind == Kind.VERSION;
    }

    public boolean isUnsupported() {
        return !supported || kind == Kind.UNSUPPORTED;
    }

    public NumericMention withLineSignature(String signature) {
        return new NumericMention(kind, value, unit, unitFamily, metricKey, objectKey, subjectKey,
                direction, qualifier, statisticKey, start, end, signature, contextSignature,
                ambiguousBinding, unsupportedRaw, supported);
    }
}
