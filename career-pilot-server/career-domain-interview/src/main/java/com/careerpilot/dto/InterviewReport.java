package com.careerpilot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试报告（《API接口设计》§7.4，V2.5 补充头部与总评字段）。
 *
 * 只读契约：`totalScore` 与总评（`overallSummary` / `strengths` / `weaknesses` /
 * `recommendations`）**均直接返回已写入的值，不做读取时惰性计算、不触发模型调用**——
 * 写入时机是提交回答成功时（§7.2/§7.4）。
 *
 * `completed` 由本次查询的题目行实时判定（全部 `answer IS NOT NULL`），
 * 未完成时 `totalScore` 与总评字段均为 null。
 */
public class InterviewReport {

    private Long sessionId;

    private String title;

    private Long jobId;

    private String jobName;

    private String companyName;

    /** M12：来源简历（可空）；名称由 resume_id 关联查询得到，**不过滤 resume.status** */
    private Long resumeId;

    private String resumeName;

    /** M12：来源优化记录与版本号（可空） */
    private Long optimizationId;

    private Integer optimizationVersionNo;

    private String questionType;

    /** 0 进行中、1 已完成、2 已取消（保留） */
    private Integer status;

    /** 计划题目数 */
    private Integer questionCount;

    /** 已作答题目数 */
    private Integer answeredCount;

    /** 全部题目均已作答（由题目行判定，与 session.status 相互独立） */
    private boolean completed;

    /** 仅在 completed 时有值（题均分，两位小数）；报告只读已写入的值 */
    private BigDecimal totalScore;

    /** 首次完成时间；未完成时为 null */
    private LocalDateTime completedAt;

    /** AI 总评；未完成或生成失败时为 null */
    private String overallSummary;

    private List<String> strengths;

    private List<String> weaknesses;

    private List<String> recommendations;

    /** M15：总评来源（AI = 模型生成；DEGRADED = 系统降级汇总）；未完成或历史数据为 null */
    private String summarySource;

    /** M15：总评生成时间（含重新生成） */
    private LocalDateTime summaryGeneratedAt;

    private List<CategoryScore> categoryScores;

    private List<ReportQuestion> questions;

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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

    public Long getOptimizationId() {
        return optimizationId;
    }

    public void setOptimizationId(Long optimizationId) {
        this.optimizationId = optimizationId;
    }

    public Integer getOptimizationVersionNo() {
        return optimizationVersionNo;
    }

    public void setOptimizationVersionNo(Integer optimizationVersionNo) {
        this.optimizationVersionNo = optimizationVersionNo;
    }

    public String getQuestionType() {
        return questionType;
    }

    public void setQuestionType(String questionType) {
        this.questionType = questionType;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public Integer getAnsweredCount() {
        return answeredCount;
    }

    public void setAnsweredCount(Integer answeredCount) {
        this.answeredCount = answeredCount;
    }

    public boolean isCompleted() {
        return completed;
    }

    public void setCompleted(boolean completed) {
        this.completed = completed;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public String getOverallSummary() {
        return overallSummary;
    }

    public void setOverallSummary(String overallSummary) {
        this.overallSummary = overallSummary;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

    public String getSummarySource() {
        return summarySource;
    }

    public void setSummarySource(String summarySource) {
        this.summarySource = summarySource;
    }

    public LocalDateTime getSummaryGeneratedAt() {
        return summaryGeneratedAt;
    }

    public void setSummaryGeneratedAt(LocalDateTime summaryGeneratedAt) {
        this.summaryGeneratedAt = summaryGeneratedAt;
    }

    public List<CategoryScore> getCategoryScores() {
        return categoryScores;
    }

    public void setCategoryScores(List<CategoryScore> categoryScores) {
        this.categoryScores = categoryScores;
    }

    public List<ReportQuestion> getQuestions() {
        return questions;
    }

    public void setQuestions(List<ReportQuestion> questions) {
        this.questions = questions;
    }

}
