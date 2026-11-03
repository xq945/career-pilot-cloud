package com.careerpilot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 面试详情（《API接口设计》§7.3）：会话字段 + 全部题目（含已提交的回答与评价）。
 *
 * 题目按 `sortOrder` 升序返回；`jobName` / `companyName` 同时用于答题页头部展示，
 * 关联查询不过滤 job.status（理由见 §6.2）。
 */
public class InterviewDetail {

    private Long id;

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

    private Integer questionCount;

    private Integer generatedCount;

    private Integer answeredCount;

    private Integer status;

    private BigDecimal totalScore;

    private LocalDateTime createdAt;

    private List<InterviewQuestionView> questions;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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

    public Integer getQuestionCount() {
        return questionCount;
    }

    public void setQuestionCount(Integer questionCount) {
        this.questionCount = questionCount;
    }

    public Integer getGeneratedCount() {
        return generatedCount;
    }

    public void setGeneratedCount(Integer generatedCount) {
        this.generatedCount = generatedCount;
    }

    public Integer getAnsweredCount() {
        return answeredCount;
    }

    public void setAnsweredCount(Integer answeredCount) {
        this.answeredCount = answeredCount;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public BigDecimal getTotalScore() {
        return totalScore;
    }

    public void setTotalScore(BigDecimal totalScore) {
        this.totalScore = totalScore;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public List<InterviewQuestionView> getQuestions() {
        return questions;
    }

    public void setQuestions(List<InterviewQuestionView> questions) {
        this.questions = questions;
    }

}
