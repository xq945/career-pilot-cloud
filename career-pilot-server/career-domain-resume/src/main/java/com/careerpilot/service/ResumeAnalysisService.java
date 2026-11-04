package com.careerpilot.service;

import java.util.List;

import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 简历分析域的服务契约（阶段 5）。
 */
public interface ResumeAnalysisService {

    ResumeAnalysisDetail analyze(Long userId, Long resumeId);

    ResumeAnalysisDetail analyze(Resume resume);

    ResumeAnalysisView getLatest(Long userId, Long resumeId);

    ResumeAnalysis findLatestSuccess(Long resumeId);

    // ---------------------------------------------------------------- 单体跨域查询（阶段 5 新增）

    /** 按 ID 取分析版本（不过滤状态，供历史记录展示） */
    ResumeAnalysis getAnalysisById(Long id);

    /** 批量取"每份简历的最新成功分析"（仪表盘/列表装配） */
    List<ResumeAnalysis> latestSuccessByResumeIds(List<Long> resumeIds);

    /** 本人简历中已完成分析的数量 */
    long countAnalyzedResumes(Long userId);

    /** 本人最近的分析记录（仪表盘时间线） */
    List<ResumeAnalysis> recentAnalyses(Long userId, int limit);
}
