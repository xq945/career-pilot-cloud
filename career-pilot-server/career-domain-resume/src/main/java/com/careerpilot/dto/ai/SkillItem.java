package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 技能项（《Agent设计》6.1、API 4.5）。
 *
 * {@code name} 为必需字段：缺失时该元素被整条丢弃（《Agent设计》6.4）。
 */
public class SkillItem extends AiOutputElement {

    private String name;

    /** 熟练程度描述（由 Prompt 约定，如「已体现」「仅提及」） */
    private String level;

    /** 简历中的依据（简短引用） */
    private String evidence;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getLevel() {
        return level;
    }

    public void setLevel(String level) {
        this.level = level;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

}
