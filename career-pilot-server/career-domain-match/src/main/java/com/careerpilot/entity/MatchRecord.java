package com.careerpilot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import com.careerpilot.dto.MatchDimensionScores;
import com.careerpilot.dto.ai.MatchItem;

/**
 * 匹配记录，对应《数据库设计》中的 match_record。
 *
 * <p>JSON 列的处理方式同 ResumeAnalysis / JobAnalysis（《数据库设计》5.5）：
 * autoResultMap + Jackson3TypeHandler。三组明细的元素类型 {@link MatchItem}、
 * 维度得分 {@link MatchDimensionScores} 均与 API 响应共用，**落库即响应、零转换**。
 *
 * <p>本表**没有 version_no 列**：响应中的 resumeVersionNo / jobVersionNo 由
 * {@code resume_analysis_id} / {@code job_analysis_id} 关联分析表读取 version_no 得到
 * （分析行不可变、外键 RESTRICT，读取即稳定）。
 *
 * <p>resume_analysis_id / job_analysis_id 为 NOT NULL（《数据库设计》5.8）：
 * REST 入口在缺分析时返回 400，Agent 工具入口会先补齐，两条路径都保证其存在。
 */
@TableName(value = "match_record", autoResultMap = true)
public class MatchRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long resumeId;

    private Long jobId;

    /** 本次匹配所依据的简历分析版本（业务上必然存在，见《数据库设计》5.8） */
    private Long resumeAnalysisId;

    /** 本次匹配所依据的岗位分析版本 */
    private Long jobAnalysisId;

    /** 0.00–100.00，**由后端规则计算**（《数据库设计》5.6；模型不能覆盖） */
    private BigDecimal matchScore;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<MatchItem> matchedItems;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<MatchItem> partialItems;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<MatchItem> missingItems;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private MatchDimensionScores dimensionScores;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> suggestions;

    private String summary;

    private LocalDateTime createdAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getResumeId() {
        return resumeId;
    }

    public void setResumeId(Long resumeId) {
        this.resumeId = resumeId;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public Long getResumeAnalysisId() {
        return resumeAnalysisId;
    }

    public void setResumeAnalysisId(Long resumeAnalysisId) {
        this.resumeAnalysisId = resumeAnalysisId;
    }

    public Long getJobAnalysisId() {
        return jobAnalysisId;
    }

    public void setJobAnalysisId(Long jobAnalysisId) {
        this.jobAnalysisId = jobAnalysisId;
    }

    public BigDecimal getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(BigDecimal matchScore) {
        this.matchScore = matchScore;
    }

    public List<MatchItem> getMatchedItems() {
        return matchedItems;
    }

    public void setMatchedItems(List<MatchItem> matchedItems) {
        this.matchedItems = matchedItems;
    }

    public List<MatchItem> getPartialItems() {
        return partialItems;
    }

    public void setPartialItems(List<MatchItem> partialItems) {
        this.partialItems = partialItems;
    }

    public List<MatchItem> getMissingItems() {
        return missingItems;
    }

    public void setMissingItems(List<MatchItem> missingItems) {
        this.missingItems = missingItems;
    }

    public MatchDimensionScores getDimensionScores() {
        return dimensionScores;
    }

    public void setDimensionScores(MatchDimensionScores dimensionScores) {
        this.dimensionScores = dimensionScores;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
