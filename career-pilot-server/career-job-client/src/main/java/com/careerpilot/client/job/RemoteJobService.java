package com.careerpilot.client.job;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobStatusRequest;
import com.careerpilot.dto.JobView;
import com.careerpilot.entity.Job;
import com.careerpilot.service.JobService;

/**
 * JobService 的远程实现（app.job.mode=remote）。
 *
 * <p>公开 CRUD 走 /api/jobs（内部令牌已由 Feign 拦截器注入）；跨域查询走 /internal/jobs。
 */
@Service
@ConditionalOnProperty(prefix = "app.job", name = "mode", havingValue = "remote")
public class RemoteJobService implements JobService {

    private final JobPublicClient publicClient;

    private final JobInternalClient internalClient;

    public RemoteJobService(JobPublicClient publicClient, JobInternalClient internalClient) {
        this.publicClient = publicClient;
        this.internalClient = internalClient;
    }

    @Override
    public JobDetail create(Long userId, JobSaveRequest request) {
        return publicClient.create(request).getData();
    }

    @Override
    public PageResult<JobView> list(Long userId, PageQuery query) {
        return publicClient.list(query.getPage(), query.getSize()).getData();
    }

    @Override
    public JobDetail detail(Long userId, Long id) {
        return publicClient.detail(id).getData();
    }

    @Override
    public JobDetail update(Long userId, Long id, JobSaveRequest request) {
        return publicClient.update(id, request).getData();
    }

    @Override
    public JobDetail updateProgressStatus(Long userId, Long id, String progressStatus) {
        JobStatusRequest request = new JobStatusRequest();
        request.setStatus(progressStatus);
        return publicClient.updateStatus(id, request).getData();
    }

    @Override
    public void delete(Long userId, Long id) {
        publicClient.delete(id);
    }

    @Override
    public Job getOwnedJob(Long userId, Long id) {
        return internalClient.getOwned(id);
    }

    @Override
    public Job getJobById(Long id) {
        return internalClient.getById(id);
    }

    @Override
    public List<Job> listOwnedJobs(Long userId) {
        return internalClient.listOwned();
    }

    @Override
    public List<Job> listJobsByIds(List<Long> ids) {
        return internalClient.listByIds(ids);
    }

    @Override
    public long countOwnedJobs(Long userId) {
        return internalClient.countOwned();
    }
}
