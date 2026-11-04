package com.careerpilot.client.job;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.dto.JobAnalysisCounts;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;

/**
 * 岗位服务的内部端点（跨域读取/分析），由 career-job 的 JobInternalController 实现。
 */
@FeignClient(name = "career-job", contextId = "jobInternal", path = "/internal/jobs")
public interface JobInternalClient {

    @GetMapping("/{id}")
    Job getOwned(@PathVariable("id") Long id);

    @GetMapping("/by-id/{id}")
    Job getById(@PathVariable("id") Long id);

    @GetMapping
    List<Job> listOwned();

    @PostMapping("/by-ids")
    List<Job> listByIds(@RequestBody List<Long> ids);

    @GetMapping("/count")
    long countOwned();

    @PostMapping("/{id}/analyze")
    JobAnalysisDetail analyze(@PathVariable("id") Long id);

    @PostMapping("/analyze-entity")
    JobAnalysisDetail analyzeEntity(@RequestBody Job job);

    @GetMapping("/{id}/analysis/latest")
    JobAnalysis findLatestSuccess(@PathVariable("id") Long id);

    @GetMapping("/{id}/analysis")
    JobAnalysisView getLatestAnalysis(@PathVariable("id") Long id);

    @GetMapping("/analysis/by-id/{id}")
    JobAnalysis getAnalysisById(@PathVariable("id") Long id);

    @PostMapping("/analysis/by-ids")
    List<JobAnalysis> getAnalysesByIds(@RequestBody List<Long> ids);

    @PostMapping("/analysis/latest-by-ids")
    List<JobAnalysis> latestSuccessByJobIds(@RequestBody List<Long> ids);

    @GetMapping("/analysis/counts")
    JobAnalysisCounts counts();

    @GetMapping("/analysis/recent")
    List<JobAnalysis> recentAnalyses(@RequestParam("limit") int limit);
}
