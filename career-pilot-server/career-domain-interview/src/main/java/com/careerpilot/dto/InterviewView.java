package com.careerpilot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面试列表项（《API接口设计》§7.3）。
 *
 * `jobName` / `companyName` 由 interview_session 关联 job 得到，**不过滤 job.status**
 * （岗位被逻辑删除后名称仍要显示，理由见 §6.2）；`generatedCount` 与 `answeredCount`
 * 由题目行的聚合统计得到（`generatedCount` 不落库，G-3）。
 *
 * 进度展示口径：分母用 `generatedCount`（**实际**题数）而非 `questionCount`（计划）——
 * 生成去重后可能少于计划，用计划数会出现「已答 5/5 却未完成」的误导（§7.3 注）。
 */
public class InterviewView {

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

    /** 计划题目数（question_count） */
    private Integer questionCount;

    /** 实际题目数（题目行数） */
    private Integer generatedCount;

    /** 已作答题目数（answer IS NOT NULL） */
    private Integer answeredCount;

    /** 0 进行中、1 已完成、2 已取消（保留） */
    private Integer status;

    /** 已完成时才有值，进行中为 null */
    private BigDecimal totalScore;

    private LocalDateTime createdAt;

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

}
