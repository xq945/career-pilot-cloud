package com.careerpilot.match.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchInternalCreateRequest;
import com.careerpilot.dto.MatchStats;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.MatchRecord;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 匹配服务的内部 API（跨域创建/读取）。
 *
 * <p>身份来自网关注入的内部令牌；属主校验仍由本地服务完成。
 */
@RestController
@RequestMapping("/internal/matches")
public class MatchInternalController {

    private final MatchService matchService;

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    public MatchInternalController(MatchService matchService, ResumeService resumeService,
                                   JobService jobService, ResumeAnalysisService resumeAnalysisService,
                                   JobAnalysisService jobAnalysisService) {
        this.matchService = matchService;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
    }

    /**
     * 创建匹配：
     * <ul>
     *   <li>未传分析 ID → Agent 入口，缺分析/过期时自动补齐；</li>
     *   <li>传了分析 ID → 按指定版本创建（优化任务快照重放；不做过期拦截，输入以调用方快照为准）。</li>
     * </ul>
     */
    @PostMapping
    public MatchDetail create(@RequestBody MatchInternalCreateRequest request) {
        Long userId = CurrentUser.requireId();
        if (request.getResumeAnalysisId() == null || request.getJobAnalysisId() == null) {
            return matchService.createForAgent(userId, request.getResumeId(), request.getJobId());
        }
        Resume resume = resumeService.getOwnedResume(userId, request.getResumeId());
        Job job = jobService.getOwnedJob(userId, request.getJobId());
        ResumeAnalysis resumeAnalysis = resumeAnalysisService.getAnalysisById(request.getResumeAnalysisId());
        JobAnalysis jobAnalysis = jobAnalysisService.getAnalysisById(request.getJobAnalysisId());
        if (resumeAnalysis == null || !resume.getId().equals(resumeAnalysis.getResumeId())
                || jobAnalysis == null || !job.getId().equals(jobAnalysis.getJobId())) {
            throw BusinessException.badRequest("匹配所依据的分析版本不合法");
        }
        return matchService.create(resume, job, resumeAnalysis, jobAnalysis);
    }

    @GetMapping("/{id}")
    public MatchRecord getOwned(@PathVariable("id") Long id) {
        return matchService.getOwnedMatch(CurrentUser.requireId(), id);
    }

    @GetMapping("/latest")
    public MatchRecord latest(@RequestParam("resumeId") Long resumeId,
                              @RequestParam("jobId") Long jobId) {
        return matchService.latestMatch(CurrentUser.requireId(), resumeId, jobId);
    }

    @GetMapping("/latest-owned")
    public MatchRecord latestOwned() {
        return matchService.latestMatchForUser(CurrentUser.requireId());
    }

    @GetMapping("/stats")
    public MatchStats stats() {
        Long userId = CurrentUser.requireId();
        return new MatchStats(matchService.countOwnedMatches(userId), matchService.bestMatchScore(userId));
    }

    @GetMapping("/recent")
    public List<MatchRecord> recent(@RequestParam("limit") int limit) {
        return matchService.recentMatches(CurrentUser.requireId(), limit);
    }
}
