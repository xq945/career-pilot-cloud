package com.careerpilot.dto;

/**
 * 单个维度的得分（《API接口设计》6.1 的 {@code dimensionScores.items} 元素）。
 *
 * <p>落库结构（match_record.dimension_scores JSON 列）与响应结构**共用本类型、零转换**
 * （《数据库设计》5.6）；因此字段名即接口键名，不得随意更名。
 */
public class MatchDimensionScore {

    /** 维度标识（五个白名单 key，取值同 {@link MatchDimension#getKey()}） */
    private String key;

    /** 维度展示名（前端直接展示，不得硬编码） */
    private String label;

    /** 权重（百分数，与配置一致：45 / 25 / 15 / 10 / 5） */
    private int weight;

    /** 该维度得分（0–100 的整数） */
    private int score;

    public MatchDimensionScore() {
    }

    public MatchDimensionScore(String key, String label, int weight, int score) {
        this.key = key;
        this.label = label;
        this.weight = weight;
        this.score = score;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getLabel() {
        return label;
    }

    public void setLabel(String label) {
        this.label = label;
    }

    public int getWeight() {
        return weight;
    }

    public void setWeight(int weight) {
        this.weight = weight;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

}
