package com.careerpilot.dto;

import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 优化任务的输入快照（阶段 7，《数据库设计》5.15）。
 *
 * <p>创建任务时固化简历原文、岗位描述、双方分析与评分规则版本；
 * 执行/重试只读快照，保证同一任务多次执行的输入一致，且源资源在排队期间被修改或删除
 * 也不影响已创建任务。旧数据快照为 null 时回退到远程查询。
 */
public class OptimizationInputSnapshot {

    private String resumeContent;

    private String jobDescription;

    private ResumeAnalysis resumeAnalysis;

    private JobAnalysis jobAnalysis;

    /** 评分规则版本（与 match_record.dimension_scores.ruleVersion 同源） */
    private String ruleVersion;

    public String getResumeContent() {
        return resumeContent;
    }

    public void setResumeContent(String resumeContent) {
        this.resumeContent = resumeContent;
    }

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
    }

    public ResumeAnalysis getResumeAnalysis() {
        return resumeAnalysis;
    }

    public void setResumeAnalysis(ResumeAnalysis resumeAnalysis) {
        this.resumeAnalysis = resumeAnalysis;
    }

    public JobAnalysis getJobAnalysis() {
        return jobAnalysis;
    }

    public void setJobAnalysis(JobAnalysis jobAnalysis) {
        this.jobAnalysis = jobAnalysis;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }
}
