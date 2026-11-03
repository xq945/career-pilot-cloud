package com.careerpilot.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.careerpilot.dto.ai.ChangeItem;
import com.careerpilot.dto.ai.MatchItem;

/**
 * 优化详情（《API接口设计》§6A.1／§6A.3，**创建、详情、保存编辑、重新评估共用同一结构**）。
 *
 * <p>优化结果的每一项都有明确出处（不靠模型复述）：
 * <ul>
 *   <li>{@code jobRequirements} —— 岗位要求分析，来自本次所依据的 {@code job_analysis} 快照；</li>
 *   <li>{@code resumeAdvantages} —— 简历优势，来自 {@code resume_analysis.advantages} 快照；</li>
 *   <li>{@code matchedItems} / {@code partialItems} / {@code missingItems} —— 岗位要求与经历的
 *       对应关系与能力差距，来自匹配解释（模型 + 账本补全）；</li>
 *   <li>{@code optimizedContent} / {@code changes} / {@code gapQuestions} —— 模型产出的优化稿、
 *       逐项修改说明与待确认问题；</li>
 *   <li>{@code matchScore} vs {@code scoreAfter} —— 优化前后匹配度，**两端均由规则计算**、
 *       使用同一份岗位分析与同一评分规则。</li>
 * </ul>
 *
 * <p>字段名即接口键名，不得随意更名。
 */
public class ResumeOptimizationDetail {

    private Long id;

    private Long resumeId;

    private String resumeName;

    private Integer resumeVersionNo;

    private Long jobId;

    private String jobName;

    private String companyName;

    private Integer jobVersionNo;

    /** 优化前匹配评估对应的 match_record.id */
    private Long matchId;

    /** 优化前总分（0.00–100.00，规则计算） */
    private double matchScore;

    private MatchDimensionScores dimensionScores;

    private List<MatchItem> matchedItems;

    private List<MatchItem> partialItems;

    private List<MatchItem> missingItems;

    /** 岗位要求分析（job_analysis 快照） */
    private JobRequirements jobRequirements;

    /** 简历优势（resume_analysis.advantages 快照） */
    private List<String> resumeAdvantages;

    /** 优化后的简历（Markdown 原稿，不可变） */
    private String optimizedContent;

    /** 用户编辑后的当前稿；null 表示尚未编辑 */
    private String editedContent;

    /** 编辑历史快照（最多 10 版；供「历史版本」抽屉） */
    private List<EditSnapshot> editHistory;

    private List<ChangeItem> changes;

    private List<String> gapQuestions;

    /** 优化后总分；null 表示尚未评估 */
    private Double scoreAfter;

    private MatchDimensionScores dimensionScoresAfter;

    private LocalDateTime evaluatedAt;

    private Integer versionNo;

    private String modelName;

    private String status;

    /** M16：任务阶段（MATCHING/GENERATING；仅 PROCESSING 有值，供详情页轮询进度展示） */
    private String stage;

    /** M16：失败原因（用户可读；仅 FAILED 有值） */
    private String errorMessage;

    /**
     * V1.13：事实校验失败的完整问题清单（脱敏；仅因校验失败的任务有值）。
     * errorMessage 只含前 5 项汇总，本字段供详情页展开全部问题。
     */
    private List<OptimizationFailureIssue> failureIssues;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

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

    public Long getMatchId() {
        return matchId;
    }

    public void setMatchId(Long matchId) {
        this.matchId = matchId;
    }

    public double getMatchScore() {
        return matchScore;
    }

    public void setMatchScore(double matchScore) {
        this.matchScore = matchScore;
    }

    public MatchDimensionScores getDimensionScores() {
        return dimensionScores;
    }

    public void setDimensionScores(MatchDimensionScores dimensionScores) {
        this.dimensionScores = dimensionScores;
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

    public JobRequirements getJobRequirements() {
        return jobRequirements;
    }

    public void setJobRequirements(JobRequirements jobRequirements) {
        this.jobRequirements = jobRequirements;
    }

    public List<String> getResumeAdvantages() {
        return resumeAdvantages;
    }

    public void setResumeAdvantages(List<String> resumeAdvantages) {
        this.resumeAdvantages = resumeAdvantages;
    }

    public String getOptimizedContent() {
        return optimizedContent;
    }

    public void setOptimizedContent(String optimizedContent) {
        this.optimizedContent = optimizedContent;
    }

    public String getEditedContent() {
        return editedContent;
    }

    public void setEditedContent(String editedContent) {
        this.editedContent = editedContent;
    }

    public List<EditSnapshot> getEditHistory() {
        return editHistory;
    }

    public void setEditHistory(List<EditSnapshot> editHistory) {
        this.editHistory = editHistory;
    }

    public List<ChangeItem> getChanges() {
        return changes;
    }

    public void setChanges(List<ChangeItem> changes) {
        this.changes = changes;
    }

    public List<String> getGapQuestions() {
        return gapQuestions;
    }

    public void setGapQuestions(List<String> gapQuestions) {
        this.gapQuestions = gapQuestions;
    }

    public Double getScoreAfter() {
        return scoreAfter;
    }

    public void setScoreAfter(Double scoreAfter) {
        this.scoreAfter = scoreAfter;
    }

    public MatchDimensionScores getDimensionScoresAfter() {
        return dimensionScoresAfter;
    }

    public void setDimensionScoresAfter(MatchDimensionScores dimensionScoresAfter) {
        this.dimensionScoresAfter = dimensionScoresAfter;
    }

    public LocalDateTime getEvaluatedAt() {
        return evaluatedAt;
    }

    public void setEvaluatedAt(LocalDateTime evaluatedAt) {
        this.evaluatedAt = evaluatedAt;
    }

    public Integer getVersionNo() {
        return versionNo;
    }

    public void setVersionNo(Integer versionNo) {
        this.versionNo = versionNo;
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

    public String getStage() {
        return stage;
    }

    public void setStage(String stage) {
        this.stage = stage;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<OptimizationFailureIssue> getFailureIssues() {
        return failureIssues;
    }

    public void setFailureIssues(List<OptimizationFailureIssue> failureIssues) {
        this.failureIssues = failureIssues;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
