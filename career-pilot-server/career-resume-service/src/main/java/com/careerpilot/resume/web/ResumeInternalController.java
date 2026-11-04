package com.careerpilot.resume.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 简历服务的内部 API（跨域读取与分析）。
 *
 * <p>身份来自网关注入的内部令牌；属主校验仍由本地服务完成。
 */
@RestController
@RequestMapping("/internal/resumes")
public class ResumeInternalController {

    private final ResumeService resumeService;

    private final ResumeAnalysisService resumeAnalysisService;

    public ResumeInternalController(ResumeService resumeService,
                                    ResumeAnalysisService resumeAnalysisService) {
        this.resumeService = resumeService;
        this.resumeAnalysisService = resumeAnalysisService;
    }

    @GetMapping("/{id}")
    public Resume getOwned(@PathVariable("id") Long id) {
        return resumeService.getOwnedResume(CurrentUser.requireId(), id);
    }

    @GetMapping("/by-id/{id}")
    public Resume getById(@PathVariable("id") Long id) {
        return resumeService.getResumeById(id);
    }

    @GetMapping
    public List<Resume> listOwned() {
        return resumeService.listOwnedResumes(CurrentUser.requireId());
    }

    @PostMapping("/by-ids")
    public List<Resume> listByIds(@RequestBody List<Long> ids) {
        return resumeService.listResumesByIds(ids);
    }

    @GetMapping("/count")
    public long countOwned() {
        return resumeService.countOwnedResumes(CurrentUser.requireId());
    }

    @PostMapping("/{id}/analyze")
    public ResumeAnalysisDetail analyze(@PathVariable("id") Long id) {
        return resumeAnalysisService.analyze(CurrentUser.requireId(), id);
    }

    @PostMapping("/analyze-entity")
    public ResumeAnalysisDetail analyzeEntity(@RequestBody Resume resume) {
        // 不信任请求体：按 ID 重新取本人有效简历后分析
        Resume owned = resumeService.getOwnedResume(CurrentUser.requireId(), resume.getId());
        return resumeAnalysisService.analyze(owned);
    }

    @GetMapping("/{id}/analysis/latest")
    public ResumeAnalysis findLatestSuccess(@PathVariable("id") Long id) {
        return resumeAnalysisService.findLatestSuccess(id);
    }

    @GetMapping("/{id}/analysis")
    public ResumeAnalysisView getLatestAnalysis(@PathVariable("id") Long id) {
        return resumeAnalysisService.getLatest(CurrentUser.requireId(), id);
    }

    @GetMapping("/analysis/by-id/{id}")
    public ResumeAnalysis getAnalysisById(@PathVariable("id") Long id) {
        return resumeAnalysisService.getAnalysisById(id);
    }

    @PostMapping("/analysis/by-ids")
    public List<ResumeAnalysis> getAnalysesByIds(@RequestBody List<Long> ids) {
        return resumeAnalysisService.getAnalysesByIds(ids);
    }

    @PostMapping("/analysis/latest-by-ids")
    public List<ResumeAnalysis> latestSuccessByResumeIds(@RequestBody List<Long> ids) {
        return resumeAnalysisService.latestSuccessByResumeIds(ids);
    }

    @GetMapping("/analysis/count")
    public long countAnalyzed() {
        return resumeAnalysisService.countAnalyzedResumes(CurrentUser.requireId());
    }

    @GetMapping("/analysis/recent")
    public List<ResumeAnalysis> recentAnalyses(@RequestParam("limit") int limit) {
        return resumeAnalysisService.recentAnalyses(CurrentUser.requireId(), limit);
    }
}
