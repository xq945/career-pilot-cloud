package com.careerpilot.client.match;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchInternalCreateRequest;
import com.careerpilot.dto.MatchStats;
import com.careerpilot.dto.MatchView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.service.MatchService;

/**
 * MatchService 的远程实现（app.match.mode=remote）。
 *
 * <p>公开 CRUD 走 /api/matches；跨域查询与 Agent 入口走 /internal/matches。
 * Agent 入口只发送简历/岗位 ID，由匹配服务完成"补齐分析 → 匹配"，
 * 避免单体与匹配服务重复分析（简历分析每次调用都会生成新版本）。
 */
@Service
@ConditionalOnProperty(prefix = "app.match", name = "mode", havingValue = "remote")
public class RemoteMatchService implements MatchService {

    private final MatchPublicClient publicClient;

    private final MatchInternalClient internalClient;

    public RemoteMatchService(MatchPublicClient publicClient, MatchInternalClient internalClient) {
        this.publicClient = publicClient;
        this.internalClient = internalClient;
    }

    @Override
    public MatchDetail create(Long userId, MatchCreateRequest request) {
        return publicClient.create(request).getData();
    }

    @Override
    public PageResult<MatchView> list(Long userId, PageQuery query) {
        return publicClient.list(query.getPage(), query.getSize()).getData();
    }

    @Override
    public MatchDetail detail(Long userId, Long id) {
        return publicClient.detail(id).getData();
    }

    @Override
    public MatchDetail create(Resume resume, Job job, ResumeAnalysis resumeAnalysis, JobAnalysis jobAnalysis) {
        MatchInternalCreateRequest request = new MatchInternalCreateRequest();
        request.setResumeId(resume.getId());
        request.setJobId(job.getId());
        request.setResumeAnalysisId(resumeAnalysis.getId());
        request.setJobAnalysisId(jobAnalysis.getId());
        return internalClient.create(request);
    }

    @Override
    public MatchDetail createForAgent(Long userId, Long resumeId, Long jobId) {
        MatchInternalCreateRequest request = new MatchInternalCreateRequest();
        request.setResumeId(resumeId);
        request.setJobId(jobId);
        return internalClient.create(request);
    }

    @Override
    public MatchRecord getOwnedMatch(Long userId, Long id) {
        return internalClient.getOwned(id);
    }

    @Override
    public MatchRecord latestMatch(Long userId, Long resumeId, Long jobId) {
        return internalClient.latest(resumeId, jobId);
    }

    @Override
    public MatchRecord latestMatchForUser(Long userId) {
        return internalClient.latestOwned();
    }

    @Override
    public long countOwnedMatches(Long userId) {
        return internalClient.stats().count();
    }

    @Override
    public Double bestMatchScore(Long userId) {
        MatchStats stats = internalClient.stats();
        return stats.bestScore();
    }

    @Override
    public List<MatchRecord> recentMatches(Long userId, int limit) {
        return internalClient.recent(limit);
    }
}
