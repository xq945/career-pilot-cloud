package com.careerpilot.dto;

/**
 * 仪表盘「能力差距概览」项（《API接口设计》9.1）。
 *
 * <p>{@code type} 由来源列表决定（match_record 的明细项本身没有 type 列）：
 * {@code missing_items} 在前（{@code MISSING}）、{@code partial_items} 在后（{@code PARTIAL}）。
 * {@code dimension} 可为 null（明细项未给出归因时）。
 *
 * <p>文案的措辞合规性由 M6 的账本补全与措辞守卫保证（差距项受控为「简历未体现」类），
 * 本接口只做投影、不做二次加工（《API接口设计》9.1 的字段口径仅要求 {@code text} 与 {@code dimension}）。
 */
public class DashboardGapItem {

    /** MISSING（简历未体现）/ PARTIAL（部分匹配） */
    private String type;

    private String text;

    /** 该明细影响的维度 key（五个白名单之一；未给出归因时为 null） */
    private String dimension;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

}
