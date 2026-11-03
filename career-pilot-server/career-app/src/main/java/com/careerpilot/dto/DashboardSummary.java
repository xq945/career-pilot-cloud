package com.careerpilot.dto;

import java.util.List;

/**
 * 仪表盘汇总（《API接口设计》9.1）：当前用户的求职准备总览。
 *
 * <p>**只读、只服务仪表盘单一页面**，不做通用化设计；全部计数带 user_id 过滤，
 * 各计数为独立查询、第一版不做缓存（9.1 的定位约定）。
 *
 * <p>口径与各模块列表严格一致（这是「仪表盘数字与各模块实际数据一致」验收的前提）：
 * <ul>
 *   <li>简历 / 岗位计数只计 {@code status = 1}（逻辑删除手写条件，不用 @TableLogic）；</li>
 *   <li>分析相关计数只计成功态（{@code status = 'SUCCESS'} 字符串）；</li>
 *   <li>{@code jobStaleCount ⊆ jobAnalyzedCount}——有分析才谈得上过期，
 *       「存在未过期分析」由 {@code jobAnalyzedCount > jobStaleCount} 判断；</li>
 *   <li>面试的 {@code status = 2}（已取消）不计入任何计数（第一版无取消入口）。</li>
 * </ul>
 */
public class DashboardSummary {

    /** 有效简历数（status = 1） */
    private Integer resumeCount;

    /** 其中已完成分析（有成功态分析记录）的简历数 */
    private Integer resumeAnalyzedCount;

    /** 有效岗位数（status = 1） */
    private Integer jobCount;

    /** 其中已完成分析的岗位数 */
    private Integer jobAnalyzedCount;

    /** 其中分析已过期（最新成功分析的 source_hash 与当前描述不一致）的岗位数 */
    private Integer jobStaleCount;

    /** 匹配记录总数 */
    private Integer matchCount;

    /** 历史最高分；无匹配记录时为 null */
    private Double bestMatchScore;

    /** 面试会话总数 */
    private Integer interviewCount;

    /** 进行中（status = 0） */
    private Integer interviewOngoingCount;

    /** 已完成（status = 1） */
    private Integer interviewCompletedCount;

    /** 最近任务时间线（M13：四类各取最近 3 条、合并倒序取 6）；无任务时为空数组 */
    private List<DashboardRecentTask> recentTasks;

    /** 最近 5 条匹配记录（created_at 倒序）；无记录时为空数组。M13 起页面不再展示，字段保留兼容 */
    private List<DashboardRecentMatch> recentMatches;

    /** 最近一次匹配的差距项（missing 在前、partial 在后，最多 3 条）；无记录时为空数组 */
    private List<DashboardGapItem> topGaps;

    public Integer getResumeCount() {
        return resumeCount;
    }

    public void setResumeCount(Integer resumeCount) {
        this.resumeCount = resumeCount;
    }

    public Integer getResumeAnalyzedCount() {
        return resumeAnalyzedCount;
    }

    public void setResumeAnalyzedCount(Integer resumeAnalyzedCount) {
        this.resumeAnalyzedCount = resumeAnalyzedCount;
    }

    public Integer getJobCount() {
        return jobCount;
    }

    public void setJobCount(Integer jobCount) {
        this.jobCount = jobCount;
    }

    public Integer getJobAnalyzedCount() {
        return jobAnalyzedCount;
    }

    public void setJobAnalyzedCount(Integer jobAnalyzedCount) {
        this.jobAnalyzedCount = jobAnalyzedCount;
    }

    public Integer getJobStaleCount() {
        return jobStaleCount;
    }

    public void setJobStaleCount(Integer jobStaleCount) {
        this.jobStaleCount = jobStaleCount;
    }

    public Integer getMatchCount() {
        return matchCount;
    }

    public void setMatchCount(Integer matchCount) {
        this.matchCount = matchCount;
    }

    public Double getBestMatchScore() {
        return bestMatchScore;
    }

    public void setBestMatchScore(Double bestMatchScore) {
        this.bestMatchScore = bestMatchScore;
    }

    public Integer getInterviewCount() {
        return interviewCount;
    }

    public void setInterviewCount(Integer interviewCount) {
        this.interviewCount = interviewCount;
    }

    public Integer getInterviewOngoingCount() {
        return interviewOngoingCount;
    }

    public void setInterviewOngoingCount(Integer interviewOngoingCount) {
        this.interviewOngoingCount = interviewOngoingCount;
    }

    public Integer getInterviewCompletedCount() {
        return interviewCompletedCount;
    }

    public void setInterviewCompletedCount(Integer interviewCompletedCount) {
        this.interviewCompletedCount = interviewCompletedCount;
    }

    public List<DashboardRecentTask> getRecentTasks() {
        return recentTasks;
    }

    public void setRecentTasks(List<DashboardRecentTask> recentTasks) {
        this.recentTasks = recentTasks;
    }

    public List<DashboardRecentMatch> getRecentMatches() {
        return recentMatches;
    }

    public void setRecentMatches(List<DashboardRecentMatch> recentMatches) {
        this.recentMatches = recentMatches;
    }

    public List<DashboardGapItem> getTopGaps() {
        return topGaps;
    }

    public void setTopGaps(List<DashboardGapItem> topGaps) {
        this.topGaps = topGaps;
    }

}
