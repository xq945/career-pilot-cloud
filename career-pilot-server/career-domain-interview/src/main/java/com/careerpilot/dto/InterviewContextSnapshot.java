package com.careerpilot.dto;

import java.util.List;

import com.careerpilot.dto.ai.MatchItem;

/**
 * 创建面试时的出题上下文快照（阶段 8，《数据库设计》interview_session.context_snapshot）。
 *
 * <p>记录**出题时实际装配**的跨域数据，用于事后追溯"这道题是在什么输入下生成的"。
 * 只存精简版：岗位结构化分析的三个技能列、简历/优化稿正文、能力差距项；
 * 不存完整岗位描述之外的原始文件、不存模型输出。
 *
 * <p>重新生成总评**不读取该快照**——总评只依赖已落库的题目、回答与评价（§4.5）。
 */
public class InterviewContextSnapshot {

    /** 岗位描述原文（出题的必选输入） */
    private String jobDescription;

    /** 岗位分析：必备技能（未分析或分析过期时为空） */
    private List<String> requiredSkills;

    /** 岗位分析：加分技能 */
    private List<String> preferredSkills;

    /** 岗位分析：技术栈 */
    private List<String> technologyStack;

    /** 来源简历正文（仅"仅提供 resumeId"路径写入；优化路径为 null） */
    private String resumeContent;

    /** 来源优化稿当前内容（仅 optimizationId 路径写入；简历路径为 null） */
    private String optimizationContent;

    /** 能力差距项（missing 在前、partial 在后；无匹配记录时为 null） */
    private List<MatchItem> matchGaps;

    public String getJobDescription() {
        return jobDescription;
    }

    public void setJobDescription(String jobDescription) {
        this.jobDescription = jobDescription;
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

    public String getResumeContent() {
        return resumeContent;
    }

    public void setResumeContent(String resumeContent) {
        this.resumeContent = resumeContent;
    }

    public String getOptimizationContent() {
        return optimizationContent;
    }

    public void setOptimizationContent(String optimizationContent) {
        this.optimizationContent = optimizationContent;
    }

    public List<MatchItem> getMatchGaps() {
        return matchGaps;
    }

    public void setMatchGaps(List<MatchItem> matchGaps) {
        this.matchGaps = matchGaps;
    }
}
