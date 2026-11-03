package com.careerpilot.entity;

import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import com.careerpilot.dto.ai.EducationItem;
import com.careerpilot.dto.ai.ExperienceItem;
import com.careerpilot.dto.ai.ProjectItem;
import com.careerpilot.dto.ai.SkillItem;

/**
 * 简历分析结果，对应《数据库设计》中的 resume_analysis。
 *
 * M4 按《数据库设计》补全了 7 个 JSON 列（V6 验证时只映射了 skills 与 suggestions）。
 * JSON 列的元素结构与模型输出（{@link com.careerpilot.dto.ai.ResumeAnalysisAiOutput}）、
 * API 响应（{@link com.careerpilot.dto.ResumeAnalysisDetail}）**共用同一套类型**，
 * 落库即响应、零转换。
 *
 * JSON 列的处理方式见《数据库设计》5.5 节：
 * 使用 MyBatis-Plus 的 JSON TypeHandler，并在 @TableName 上开启 autoResultMap，
 * 否则查询时不会走 TypeHandler（这是 MyBatis-Plus 的常见坑）。
 * Jackson3TypeHandler 注册时拿到字段的 Field（含泛型信息），因此 List<元素类型> 可正确读回。
 *
 * Spring Boot 4 环境下使用 Jackson3TypeHandler（对应 tools.jackson 包）。
 */
@TableName(value = "resume_analysis", autoResultMap = true)
public class ResumeAnalysis {

    /** 分析成功状态（《数据库设计》5.3：第一版只保存成功结果） */
    public static final String STATUS_SUCCESS = "SUCCESS";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    /** 同一简历的分析版本号，从 1 递增（每次分析都产生新版本） */
    private Integer versionNo;

    /** 分析时简历文本的 SHA-256（对入库后的 resume.content 计算），数据追溯用 */
    private String sourceHash;

    private String summary;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<SkillItem> skills;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<EducationItem> education;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<ProjectItem> projects;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<ExperienceItem> experience;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> advantages;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> weaknesses;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> suggestions;

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

    public String getSourceHash() {
        return sourceHash;
    }

    public void setSourceHash(String sourceHash) {
        this.sourceHash = sourceHash;
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
