package com.careerpilot.service;

import java.util.List;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 匹配域的服务契约（阶段 7）：本地实现留在 career-match 服务，单体侧由远程实现调用。
 */
public interface MatchService {

    // ---------------------------------------------------------------- 公开 API（6.1–6.3）

    MatchDetail create(Long userId, MatchCreateRequest request);

    PageResult<MatchView> list(Long userId, PageQuery query);

    MatchDetail detail(Long userId, Long id);

    // ---------------------------------------------------------------- 核心入口

    /**
     * 核心入口（优化链路与测试复用）：入参为**已归属校验**的实体。
     */
    MatchDetail create(Resume resume, Job job, ResumeAnalysis resumeAnalysis, JobAnalysis jobAnalysis);

    /**
     * Agent 入口：缺分析或岗位分析过期时**自动补齐**再匹配（与 REST 的刻意差异）。
     */
    MatchDetail createForAgent(Long userId, Long resumeId, Long jobId);

    // ---------------------------------------------------------------- 跨域查询（阶段 7 新增）

    /** 归属校验：不存在或属于他人一律 404 */
    MatchRecord getOwnedMatch(Long userId, Long id);

    /** 「简历 × 岗位」的最近一条匹配（无则 null；面试上下文用） */
    MatchRecord latestMatch(Long userId, Long resumeId, Long jobId);

    /** 本人最近一条匹配（无则 null；仪表盘差距概览用） */
    MatchRecord latestMatchForUser(Long userId);

    /** 本人匹配记录数（仪表盘） */
    long countOwnedMatches(Long userId);

    /** 本人历史最高分；无记录时 null（仪表盘） */
    Double bestMatchScore(Long userId);

    /** 本人最近 N 条匹配，created_at 倒序（仪表盘） */
    List<MatchRecord> recentMatches(Long userId, int limit);
}
