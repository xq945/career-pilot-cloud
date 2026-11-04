package com.careerpilot.client.resume;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;

/**
 * 简历服务的内部端点（跨域读取/分析），由 career-resume 的 ResumeInternalController 实现。
 */
@FeignClient(name = "career-resume", contextId = "resumeInternal", path = "/internal/resumes")
public interface ResumeInternalClient {

    @GetMapping("/{id}")
    Resume getOwned(@PathVariable("id") Long id);

    @GetMapping("/by-id/{id}")
    Resume getById(@PathVariable("id") Long id);

    @GetMapping
    List<Resume> listOwned();

    @PostMapping("/by-ids")
    List<Resume> listByIds(@RequestBody List<Long> ids);

    @GetMapping("/count")
    long countOwned();

    @PostMapping("/{id}/analyze")
    ResumeAnalysisDetail analyze(@PathVariable("id") Long id);

    @PostMapping("/analyze-entity")
    ResumeAnalysisDetail analyzeEntity(@RequestBody Resume resume);

    @GetMapping("/{id}/analysis/latest")
    ResumeAnalysis findLatestSuccess(@PathVariable("id") Long id);

    @GetMapping("/{id}/analysis")
    ResumeAnalysisView getLatestAnalysis(@PathVariable("id") Long id);

    @GetMapping("/analysis/by-id/{id}")
    ResumeAnalysis getAnalysisById(@PathVariable("id") Long id);

    @PostMapping("/analysis/by-ids")
    List<ResumeAnalysis> getAnalysesByIds(@RequestBody List<Long> ids);

    @PostMapping("/analysis/latest-by-ids")
    List<ResumeAnalysis> latestSuccessByResumeIds(@RequestBody List<Long> ids);

    @GetMapping("/analysis/count")
    long countAnalyzed();

    @GetMapping("/analysis/recent")
    List<ResumeAnalysis> recentAnalyses(@RequestParam("limit") int limit);
}
