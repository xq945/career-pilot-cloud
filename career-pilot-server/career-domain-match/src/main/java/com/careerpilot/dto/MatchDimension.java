package com.careerpilot.dto;

/**
 * 匹配维度（《数据库设计》6.1、《API接口设计》6.1 的五个白名单 key）。
 *
 * <p>枚举声明顺序即**同权重时的稳定兜底顺序**：维度输出按权重降序排列，权重相同时按本顺序。
 *
 * <p>该枚举同时服务于三处：评分与排序、模型输出的清洗（dimension 越界置 null，
 * 《Agent设计》6.4）、以及展示（label 由接口下发，前端不得硬编码）。
 */
public enum MatchDimension {

    REQUIRED_SKILLS("requiredSkills", "必备技能"),

    PROJECT_RELEVANCE("projectRelevance", "项目经历相关性"),

    EXPERIENCE("experience", "工作/实习经历"),

    EDUCATION("education", "学历与经验要求"),

    PREFERRED_SKILLS("preferredSkills", "加分技能");

    private final String key;

    private final String label;

    MatchDimension(String key, String label) {
        this.key = key;
        this.label = label;
    }

    public String getKey() {
        return key;
    }

    public String getLabel() {
        return label;
    }

    /**
     * key → 枚举；**越界或 null 返回 null（不抛异常）**。
     * 模型输出的 dimension 清洗依赖此约定：越界置 null 而非整条丢弃。
     */
    public static MatchDimension fromKey(String key) {
        for (MatchDimension dimension : values()) {
            if (dimension.key.equals(key)) {
                return dimension;
            }
        }
        return null;
    }

}
