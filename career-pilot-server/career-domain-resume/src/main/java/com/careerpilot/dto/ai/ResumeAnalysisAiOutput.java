package com.careerpilot.dto.ai;

import java.util.List;

/**
 * 简历分析的模型输出结构（《Agent设计》6.1 的 JSON 结构）。
 *
 * 与落库结构（{@link com.careerpilot.entity.ResumeAnalysis} 的 JSON 列）
 * 及响应结构（{@link com.careerpilot.dto.ResumeAnalysisDetail} 的内容字段）**逐字段一致**，
 * 三处一套类型、零转换。
 *
 * 该类型同时用于生成 JSON Schema（由 Spring AI 的 BeanOutputConverter 注入提示词），
 * 因此字段名即模型必须遵守的键名，不得随意更名。
 */
public class ResumeAnalysisAiOutput {

    /** 简历概述（≤ 2000 字符，超长截断） */
    private String summary;

    private List<SkillItem> skills;

    private List<EducationItem> education;

    private List<ProjectItem> projects;

    private List<ExperienceItem> experience;

    /** 优势 */
    private List<String> advantages;

    /** 待改进项 */
    private List<String> weaknesses;

    /** 改进建议 */
    private List<String> suggestions;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<SkillItem> getSkills() {
        return skills;
    }

    public void setSkills(List<SkillItem> skills) {
        this.skills = skills;
    }

    public List<EducationItem> getEducation() {
        return education;
    }

    public void setEducation(List<EducationItem> education) {
        this.education = education;
    }

    public List<ProjectItem> getProjects() {
        return projects;
    }

    public void setProjects(List<ProjectItem> projects) {
        this.projects = projects;
    }

    public List<ExperienceItem> getExperience() {
        return experience;
    }

    public void setExperience(List<ExperienceItem> experience) {
        this.experience = experience;
    }

    public List<String> getAdvantages() {
        return advantages;
    }

    public void setAdvantages(List<String> advantages) {
        this.advantages = advantages;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

}
