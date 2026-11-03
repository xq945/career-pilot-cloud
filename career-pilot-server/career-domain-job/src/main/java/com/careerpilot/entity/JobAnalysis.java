package com.careerpilot.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

/**
 * 岗位分析结果，对应《数据库设计》中的 job_analysis。
 *
 * 5 个 JSON 列的元素均为**纯字符串**（《API接口设计》5.6 定死 string[]），
 * 因此不需要元素 DTO 类；模型输出（{@link com.careerpilot.dto.ai.JobAnalysisAiOutput}）、
 * 本实体与 API 响应（{@link com.careerpilot.dto.JobAnalysisDetail}）共用同一套字段，落库即响应、零转换。
 *
 * JSON 列的处理方式见《数据库设计》5.5：使用 MyBatis-Plus 的 JSON TypeHandler，
 * 并在 @TableName 上开启 autoResultMap，否则查询时不会走 TypeHandler（MyBatis-Plus 的常见坑）。
 *
 * source_hash 的用途（《数据库设计》5.2）：判断分析是否已过期（stale）与数据追溯。
 */
@TableName(value = "job_analysis", autoResultMap = true)
public class JobAnalysis {

    /** 分析成功状态（《数据库设计》5.3：第一版只保存成功结果） */
    public static final String STATUS_SUCCESS = "SUCCESS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;

    /** 同一岗位的分析版本号，从 1 递增（每次分析都产生新版本） */
    private Integer versionNo;

    /** 分析时岗位描述的 SHA-256，用于判断分析是否过期（stale） */
    private String sourceHash;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> responsibilities;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> requiredSkills;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> preferredSkills;

    private String educationRequirement;

    private String experienceRequirement;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> technologyStack;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> softSkills;

    private String summary;

    /** 实际调用的模型名（成本核算与展示用） */
    private String modelName;

    /** 固定 SUCCESS（第一版只保存成功结果） */
    private String status;

    /** 仅排查用，不向前端返回；第一版恒为 null */
    private String errorMessage;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
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

    public String getModelName() {
        return modelName;
    }

    public void setModelName(String modelName) {
        this.modelName = modelName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
