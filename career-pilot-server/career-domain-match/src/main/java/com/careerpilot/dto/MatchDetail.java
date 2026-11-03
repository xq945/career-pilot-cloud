package com.careerpilot.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.careerpilot.dto.ai.MatchItem;

/**
 * 匹配报告详情（《API接口设计》6.1，**创建响应与详情接口共用同一结构**）。
 *
 * <p>{@code resumeName/jobName/companyName} 由关联查询得到，**源资源被逻辑删除后仍正常返回**；
 * {@code resumeVersionNo/jobVersionNo} 为本次匹配所依据的分析版本号（从分析表读取——
 * match_record 本身没有 version 列），供报告页"匹配依据"展示。
 *
 * <p>字段名即接口键名，不得随意更名。
 */
public class MatchDetail {

    private Long id;

    private Long resumeId;

    private String resumeName;

    private Integer resumeVersionNo;

    private Long jobId;

    private String jobName;

    private String companyName;

    private Integer jobVersionNo;

    /**
     * 0.00–100.00，**由后端规则计算**（见 MatchScoringService）；模型不能覆盖。
     * 用 double 而非 BigDecimal：避免 JSON 出现 76.50 这类尾零（库中仍为 DECIMAL(5,2)）。
     */
    private double matchScore;

    private List<MatchItem> matchedItems;

    private List<MatchItem> partialItems;

    private List<MatchItem> missingItems;

    private MatchDimensionScores dimensionScores;

    private String summary;

    private List<String> suggestions;

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

    public String getResumeName() {
        return resumeName;
    }

    public void setResumeName(String resumeName) {
        this.resumeName = resumeName;
    }

    public Integer getResumeVersionNo() {
        return resumeVersionNo;
    }

    public void setResumeVersionNo(Integer resumeVersionNo) {
        this.resumeVersionNo = resumeVersionNo;
    }

    public Long getJobId() {
        return jobId;
    }

    public void setJobId(Long jobId) {
        this.jobId = jobId;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public Integer getJobVersionNo() {
        return jobVersionNo;
    }

    public void setJobVersionNo(Integer jobVersionNo) {
        this.jobVersionNo = jobVersionNo;
    }

    public double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(double matchScore) {
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

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
