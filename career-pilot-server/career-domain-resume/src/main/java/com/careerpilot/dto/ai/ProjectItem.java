package com.careerpilot.dto.ai;

import java.util.List;

import com.careerpilot.ai.AiOutputElement;

/**
 * 项目经历（《Agent设计》6.1、API 4.5）：字段均可空，technology 列表可为空数组。
 */
public class ProjectItem extends AiOutputElement {

    private String name;

    private String role;

    private String description;

    /** 使用的技术栈 */
    private List<String> technologies;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTechnologies() {
        return technologies;
    }

    public void setTechnologies(List<String> technologies) {
        this.technologies = technologies;
    }

}
