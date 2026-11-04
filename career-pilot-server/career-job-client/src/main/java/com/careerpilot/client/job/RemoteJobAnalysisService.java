package com.careerpilot.client.job;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.service.JobAnalysisService;

/**
 * JobAnalysisService 的远程实现（app.job.mode=remote）。
 */
@Service
@ConditionalOnProperty(prefix = "app.job", name = "mode", havingValue = "remote")
public class RemoteJobAnalysisService implements JobAnalysisService {

    private final JobInternalClient internalClient;

    public RemoteJobAnalysisService(JobInternalClient internalClient) {
        this.internalClient = internalClient;
    }

    @Override
    public JobAnalysisDetail analyze(Long userId, Long jobId) {
        return internalClient.analyze(jobId);
    }

    @Override
    public JobAnalysisDetail analyze(Job job) {
        return internalClient.analyzeEntity(job);
    }

    @Override
    public JobAnalysisView getLatest(Long userId, Long jobId) {
        return internalClient.getLatestAnalysis(jobId);
    }

    @Override
    public JobAnalysis findLatestSuccess(Long jobId) {
        return internalClient.findLatestSuccess(jobId);
    }

    @Override
    public JobAnalysis getAnalysisById(Long id) {
        return internalClient.getAnalysisById(id);
    }

    @Override
    public List<JobAnalysis> getAnalysesByIds(List<Long> ids) {
        return internalClient.getAnalysesByIds(ids);
    }

    @Override
    public List<JobAnalysis> latestSuccessByJobIds(List<Long> jobIds) {
        return internalClient.latestSuccessByJobIds(jobIds);
    }

    @Override
    public long countAnalyzedJobs(Long userId) {
        return internalClient.counts().analyzed();
    }

    @Override
    public long countStaleJobs(Long userId) {
        return internalClient.counts().stale();
    }

    @Override
    public List<JobAnalysis> recentAnalyses(Long userId, int limit) {
        return internalClient.recentAnalyses(limit);
    }
}
