package com.careerpilot.job.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.dto.JobAnalysisCounts;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;

/**
 * 岗位服务的内部 API（跨域读取与分析）。
 *
 * <p>身份来自网关注入的内部令牌；属主校验仍由本地服务完成。
 */
@RestController
@RequestMapping("/internal/jobs")
public class JobInternalController {

    private final JobService jobService;

    private final JobAnalysisService jobAnalysisService;

    public JobInternalController(JobService jobService, JobAnalysisService jobAnalysisService) {
        this.jobService = jobService;
        this.jobAnalysisService = jobAnalysisService;
    }

    @GetMapping("/{id}")
    public Job getOwned(@PathVariable("id") Long id) {
        return jobService.getOwnedJob(CurrentUser.requireId(), id);
    }

    @GetMapping("/by-id/{id}")
    public Job getById(@PathVariable("id") Long id) {
        return jobService.getJobById(id);
    }

    @GetMapping
    public List<Job> listOwned() {
        return jobService.listOwnedJobs(CurrentUser.requireId());
    }

    @PostMapping("/by-ids")
    public List<Job> listByIds(@RequestBody List<Long> ids) {
        return jobService.listJobsByIds(ids);
    }

    @GetMapping("/count")
    public long countOwned() {
        return jobService.countOwnedJobs(CurrentUser.requireId());
    }

    @PostMapping("/{id}/analyze")
    public JobAnalysisDetail analyze(@PathVariable("id") Long id) {
        return jobAnalysisService.analyze(CurrentUser.requireId(), id);
    }

    @PostMapping("/analyze-entity")
    public JobAnalysisDetail analyzeEntity(@RequestBody Job job) {
        // 不信任请求体：按 ID 重新取本人有效岗位后分析
        Job owned = jobService.getOwnedJob(CurrentUser.requireId(), job.getId());
        return jobAnalysisService.analyze(owned);
    }

    @GetMapping("/{id}/analysis/latest")
    public JobAnalysis findLatestSuccess(@PathVariable("id") Long id) {
        return jobAnalysisService.findLatestSuccess(id);
    }

    @GetMapping("/{id}/analysis")
    public JobAnalysisView getLatestAnalysis(@PathVariable("id") Long id) {
        return jobAnalysisService.getLatest(CurrentUser.requireId(), id);
    }

    @GetMapping("/analysis/by-id/{id}")
    public JobAnalysis getAnalysisById(@PathVariable("id") Long id) {
        return jobAnalysisService.getAnalysisById(id);
    }

    @PostMapping("/analysis/by-ids")
    public List<JobAnalysis> getAnalysesByIds(@RequestBody List<Long> ids) {
        return jobAnalysisService.getAnalysesByIds(ids);
    }

    @PostMapping("/analysis/latest-by-ids")
    public List<JobAnalysis> latestSuccessByJobIds(@RequestBody List<Long> ids) {
        return jobAnalysisService.latestSuccessByJobIds(ids);
    }

    @GetMapping("/analysis/counts")
    public JobAnalysisCounts counts() {
        Long userId = CurrentUser.requireId();
        return new JobAnalysisCounts(
                jobAnalysisService.countAnalyzedJobs(userId),
                jobAnalysisService.countStaleJobs(userId));
    }

    @GetMapping("/analysis/recent")
    public List<JobAnalysis> recentAnalyses(@RequestParam("limit") int limit) {
        return jobAnalysisService.recentAnalyses(CurrentUser.requireId(), limit);
    }
}
