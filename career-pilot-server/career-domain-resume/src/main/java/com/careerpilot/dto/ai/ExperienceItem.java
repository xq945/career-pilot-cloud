package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 工作 / 实习经历（《Agent设计》6.1、API 4.5）：字段均可空。
 */
public class ExperienceItem extends AiOutputElement {

    private String company;

    private String position;

    private String period;

    private String description;

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public String getPeriod() {
        return period;
    }

    public void setPeriod(String period) {
        this.period = period;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

}
