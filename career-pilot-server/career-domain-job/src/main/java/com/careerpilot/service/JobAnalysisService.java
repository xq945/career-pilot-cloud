package com.careerpilot.service;

import java.util.List;

import com.careerpilot.common.Hashing;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;

/**
 * 岗位分析域的服务契约（阶段 5）。
 *
 * <p>{@code isStale} / {@code sameSource} 是纯函数，保留为接口静态方法：
 * 所有调用方（列表、详情、匹配、面试、优化、Agent）共用同一个判定表达式。
 */
public interface JobAnalysisService {

    JobAnalysisDetail analyze(Long userId, Long jobId);

    JobAnalysisDetail analyze(Job job);

    JobAnalysisView getLatest(Long userId, Long jobId);

    JobAnalysis findLatestSuccess(Long jobId);

    // ---------------------------------------------------------------- 单体跨域查询（阶段 5 新增）

    /** 按 ID 取分析版本（不过滤状态，供历史记录展示） */
    JobAnalysis getAnalysisById(Long id);

    /** 批量按 ID 取分析版本（匹配列表的版本号装配） */
    List<JobAnalysis> getAnalysesByIds(List<Long> ids);

    /** 批量取"每个岗位的最新成功分析"（仪表盘/列表装配） */
    List<JobAnalysis> latestSuccessByJobIds(List<Long> jobIds);

    /** 本人岗位中已完成分析的数量 */
    long countAnalyzedJobs(Long userId);

    /** 本人岗位中分析已过期的数量 */
    long countStaleJobs(Long userId);

    /** 本人最近的分析记录（仪表盘时间线） */
    List<JobAnalysis> recentAnalyses(Long userId, int limit);

    // ---------------------------------------------------------------- 纯函数

    static boolean sameSource(String currentHash, JobAnalysis latestSuccess) {
        return latestSuccess != null && currentHash.equals(latestSuccess.getSourceHash());
    }

    static boolean isStale(String description, JobAnalysis latestSuccess) {
        return latestSuccess != null && !sameSource(Hashing.sha256Hex(description), latestSuccess);
    }
}
