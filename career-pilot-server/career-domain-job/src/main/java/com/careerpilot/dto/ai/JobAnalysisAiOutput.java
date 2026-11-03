package com.careerpilot.dto.ai;

import java.util.List;

/**
 * 岗位分析的模型输出结构（《Agent设计》6.2 的 JSON 结构）。
 *
 * 与落库结构（{@link com.careerpilot.entity.JobAnalysis} 的 JSON 列）
 * 及响应结构（{@link com.careerpilot.dto.JobAnalysisDetail} 的内容字段）**逐字段一致**，
 * 三处一套类型、零转换。
 *
 * 该类型同时用于生成 JSON Schema（由 Spring AI 的 BeanOutputConverter 注入提示词），
 * 因此字段名即模型必须遵守的键名，不得随意更名。
 *
 * 所有列表字段的元素均为**纯字符串**（《API接口设计》5.6 定死 string[]）。
 */
public class JobAnalysisAiOutput {

    /** 岗位职责 */
    private List<String> responsibilities;

    /** 必备技能 */
    private List<String> requiredSkills;

    /** 加分技能 */
    private List<String> preferredSkills;

    /** 学历要求（≤ 255 字符，超长截断） */
    private String educationRequirement;

    /** 经验要求（≤ 255 字符，超长截断） */
    private String experienceRequirement;

    /** 技术栈 */
    private List<String> technologyStack;

    /** 软性要求 */
    private List<String> softSkills;

    /** 岗位概述（≤ 2000 字符，超长截断） */
    private String summary;

    public List<String> getResponsibilities() {
        return responsibilities;
    }

    public void setResponsibilities(List<String> responsibilities) {
        this.responsibilities = responsibilities;
    }

    public List<String> getRequiredSkills() {
        return requiredSkills;
    }

    public void setRequiredSkills(List<String> requiredSkills) {
        this.requiredSkills = requiredSkills;
    }

    public List<String> getPreferredSkills() {
        return preferredSkills;
    }

    public void setPreferredSkills(List<String> preferredSkills) {
        this.preferredSkills = preferredSkills;
    }

    public String getEducationRequirement() {
        return educationRequirement;
    }

    public void setEducationRequirement(String educationRequirement) {
        this.educationRequirement = educationRequirement;
    }

    public String getExperienceRequirement() {
        return experienceRequirement;
    }

    public void setExperienceRequirement(String experienceRequirement) {
        this.experienceRequirement = experienceRequirement;
    }

    public List<String> getTechnologyStack() {
        return technologyStack;
    }

    public void setTechnologyStack(List<String> technologyStack) {
        this.technologyStack = technologyStack;
    }

    public List<String> getSoftSkills() {
        return softSkills;
    }

    public void setSoftSkills(List<String> softSkills) {
        this.softSkills = softSkills;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

}
