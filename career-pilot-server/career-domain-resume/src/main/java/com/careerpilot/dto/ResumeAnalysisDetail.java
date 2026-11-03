package com.careerpilot.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.careerpilot.dto.ai.EducationItem;
import com.careerpilot.dto.ai.ExperienceItem;
import com.careerpilot.dto.ai.ProjectItem;
import com.careerpilot.dto.ai.SkillItem;

/**
 * 简历分析结果视图（《API接口设计》4.5 的成功响应 data，4.6 的 analysis 同一结构）。
 *
 * 字段与《API接口设计》4.5 的字段表逐项对应；不含 errorMessage 等内部字段。
 */
public class ResumeAnalysisDetail {

    /** 分析记录 ID（resume_analysis.id） */
    private Long analysisId;

    private Long resumeId;

    private Integer versionNo;

    /** 固定 SUCCESS（第一版只保存成功结果） */
    private String status;

    private String summary;

    private List<SkillItem> skills;

    private List<EducationItem> education;

    private List<ProjectItem> projects;

    private List<ExperienceItem> experience;

    private List<String> advantages;

    private List<String> weaknesses;

    private List<String> suggestions;

    /** 实际调用的模型名 */
    private String modelName;

    private LocalDateTime createdAt;

    public Long getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(Long analysisId) {
        this.analysisId = analysisId;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
