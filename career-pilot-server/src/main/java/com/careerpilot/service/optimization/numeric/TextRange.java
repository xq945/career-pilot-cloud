package com.careerpilot.service.optimization.numeric;

/** 半开文本区间 [start, end)，用于覆盖校验告诉数字守卫哪些块已证明事实等价。 */
public record TextRange(int start, int end) {

    public boolean contains(int position) {
        return position >= start && position < end;
    }
}
