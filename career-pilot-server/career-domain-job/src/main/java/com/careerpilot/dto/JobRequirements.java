package com.careerpilot.dto;

import java.util.List;

/**
 * 岗位要求分析（《API接口设计》§6A.1 的 {@code jobRequirements}）。
 *
 * <p>数据来自本次优化所依据的 {@code job_analysis} 快照（`match_record.job_analysis_id`
 * 指向，只读、稳定），**不由模型复述**——保证"优化结果中的岗位要求分析"可追溯到
 * 用户实际分析过的那一份岗位描述。
 *
 * <p>字段与 {@code GET /api/jobs/{id}/analysis} 的分析结果同构（少版本与时间字段）。
 */
public class JobRequirements {

    private String summary;

    private List<String> responsibilities;

    private List<String> requiredSkills;

    private List<String> preferredSkills;

    private List<String> technologyStack;

    private List<String> softSkills;

    private String educationRequirement;

    private String experienceRequirement;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

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

}
