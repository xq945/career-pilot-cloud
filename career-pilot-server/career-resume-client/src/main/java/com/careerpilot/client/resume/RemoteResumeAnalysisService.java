package com.careerpilot.client.resume;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.dto.ResumeAnalysisDetail;
import com.careerpilot.dto.ResumeAnalysisView;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.service.ResumeAnalysisService;

/**
 * ResumeAnalysisService 的远程实现（app.resume.mode=remote）。
 */
@Service
@ConditionalOnProperty(prefix = "app.resume", name = "mode", havingValue = "remote")
public class RemoteResumeAnalysisService implements ResumeAnalysisService {

    private final ResumeInternalClient internalClient;

    public RemoteResumeAnalysisService(ResumeInternalClient internalClient) {
        this.internalClient = internalClient;
    }

    @Override
    public ResumeAnalysisDetail analyze(Long userId, Long resumeId) {
        return internalClient.analyze(resumeId);
    }

    @Override
    public ResumeAnalysisDetail analyze(Resume resume) {
        return internalClient.analyzeEntity(resume);
    }

    @Override
    public ResumeAnalysisView getLatest(Long userId, Long resumeId) {
        return internalClient.getLatestAnalysis(resumeId);
    }

    @Override
    public ResumeAnalysis findLatestSuccess(Long resumeId) {
        return internalClient.findLatestSuccess(resumeId);
    }

    @Override
    public ResumeAnalysis getAnalysisById(Long id) {
        return internalClient.getAnalysisById(id);
    }

    @Override
    public List<ResumeAnalysis> getAnalysesByIds(List<Long> ids) {
        return internalClient.getAnalysesByIds(ids);
    }

    @Override
    public List<ResumeAnalysis> latestSuccessByResumeIds(List<Long> resumeIds) {
        return internalClient.latestSuccessByResumeIds(resumeIds);
    }

    @Override
    public long countAnalyzedResumes(Long userId) {
        return internalClient.countAnalyzed();
    }

    @Override
    public List<ResumeAnalysis> recentAnalyses(Long userId, int limit) {
        return internalClient.recentAnalyses(limit);
    }
}
