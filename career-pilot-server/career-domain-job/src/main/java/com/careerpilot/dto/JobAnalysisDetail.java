package com.careerpilot.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 岗位分析结果（《API接口设计》5.6 与 5.7 的 analysis 结构）。
 *
 * 字段 = 模型输出（{@link com.careerpilot.dto.ai.JobAnalysisAiOutput}）
 *  + 元数据，与落库实体共用同一套 JSON 列类型，零转换。
 *
 * **不含** `sourceHash` 与 `errorMessage`——前者是服务端的复用/过期判定依据，
 * 不向前端暴露；后者仅排查用（与 M4 的 ResumeAnalysisDetail 同）。
 */
public class JobAnalysisDetail {

    private Long analysisId;

    private Long jobId;

    private Integer versionNo;

    /** 固定 SUCCESS（第一版只保存成功结果） */
    private String status;

    private List<String> responsibilities;

    private List<String> requiredSkills;

    private List<String> preferredSkills;

    private List<String> technologyStack;

    private List<String> softSkills;

    /** 学历要求，可空 */
    private String educationRequirement;

    /** 经验要求，可空 */
    private String experienceRequirement;

    /** 岗位概述，可空 */
    private String summary;

    private String modelName;

    private LocalDateTime createdAt;

    public Long getAnalysisId() {
        return analysisId;
    }

    public void setAnalysisId(Long analysisId) {
        this.analysisId = analysisId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
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
