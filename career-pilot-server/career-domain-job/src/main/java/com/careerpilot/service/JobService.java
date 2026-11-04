package com.careerpilot.service;

import java.util.List;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobView;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;

/**
 * 岗位域的服务契约（阶段 5）：本地实现留在岗位服务，单体侧由远程实现调用。
 *
 * <p>接口化的目的：让调用方（match/interview/agent/dashboard/optimization）
 * 不感知 Job 域是本地还是远程，从而可以在不改变方法签名的前提下切换到 Feign。
 */
public interface JobService {

    JobDetail create(Long userId, JobSaveRequest request);

    PageResult<JobView> list(Long userId, PageQuery query);

    JobDetail detail(Long userId, Long id);

    JobDetail update(Long userId, Long id, JobSaveRequest request);

    JobDetail updateProgressStatus(Long userId, Long id, String progressStatus);

    void delete(Long userId, Long id);

    /** 归属校验：不存在或属于他人一律 404 */
    Job getOwnedJob(Long userId, Long id);

    // ---------------------------------------------------------------- 单体跨域查询（阶段 5 新增）

    /** 按 ID 取岗位（**不过滤 status**，供历史记录/聚合展示）；不存在返回 null */
    Job getJobById(Long id);

    /** 本人有效岗位列表（Agent 上下文、聚合用） */
    List<Job> listOwnedJobs(Long userId);

    /** 批量取岗位（不过滤 status，供列表装配） */
    List<Job> listJobsByIds(List<Long> ids);

    /** 本人有效岗位数（仪表盘） */
    long countOwnedJobs(Long userId);
}
