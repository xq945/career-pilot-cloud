package com.careerpilot.dto;

import java.util.List;

/**
 * 维度得分集合（《数据库设计》5.6 的落库结构、《API接口设计》6.1 的 {@code dimensionScores}）：
 * {@code {"ruleVersion": "v1", "items": [...]}}，**落库即响应、零转换**。
 *
 * <p>items 的顺序即最终展示顺序（按权重降序，服务端保证）；前端直接遍历渲染，
 * 不得硬编码权重与顺序（《前端原型设计》5.8）。
 */
public class MatchDimensionScores {

    /** 本次使用的评分规则版本（《数据库设计》5.6：随报告记录） */
    private String ruleVersion;

    private List<MatchDimensionScore> items;

    public MatchDimensionScores() {
    }

    public MatchDimensionScores(String ruleVersion, List<MatchDimensionScore> items) {
        this.ruleVersion = ruleVersion;
        this.items = items;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public List<MatchDimensionScore> getItems() {
        return items;
    }

    public void setItems(List<MatchDimensionScore> items) {
        this.items = items;
    }

}
