package com.careerpilot.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobAnalysisDetail;
import com.careerpilot.dto.JobAnalysisView;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobStatusRequest;
import com.careerpilot.dto.JobView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;

import jakarta.validation.Valid;

/**
 * 岗位接口（《API接口设计》5.1–5.8）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId（《系统架构设计》4.1）。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;

    private final JobAnalysisService jobAnalysisService;

    public JobController(JobService jobService, JobAnalysisService jobAnalysisService) {
        this.jobService = jobService;
        this.jobAnalysisService = jobAnalysisService;
    }

    /** 创建岗位（5.1）：responses data 为岗位详情，analyzed/stale 恒为 false */
    @PostMapping
    public ApiResponse<JobDetail> create(@Valid @RequestBody JobSaveRequest request) {
        return ApiResponse.success(jobService.create(CurrentUser.requireId(), request));
    }

    /** 岗位列表（5.2）：分页参数越界返回 40001；每条带 analyzed/stale/updatedAt，不含 description */
    @GetMapping
    public ApiResponse<PageResult<JobView>> list(@Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(jobService.list(CurrentUser.requireId(), query));
    }

    /** 岗位详情（5.3）：含完整描述（编辑回填用） */
    @GetMapping("/{id}")
    public ApiResponse<JobDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(jobService.detail(CurrentUser.requireId(), id));
    }

    /**
     * 更新岗位（5.4）：**全量更新**——jobName 与 description 必填（校验同创建），
     * companyName 省略或传 null 表示清空；描述变更后旧分析即标记为过期（stale）。
     */
    @PutMapping("/{id}")
    public ApiResponse<JobDetail> update(@PathVariable Long id,
                                         @Valid @RequestBody JobSaveRequest request) {
        return ApiResponse.success(jobService.update(CurrentUser.requireId(), id, request));
    }

    /**
     * 求职进度切换（5.8，M21）：只改状态，不动描述与其他字段；返回最新详情。
     *
     * 八个枚举值之外的输入由 DTO 的 `@Pattern` 返回 40001，服务层再兜底一次。
     */
    @PutMapping("/{id}/status")
    public ApiResponse<JobDetail> updateProgressStatus(@PathVariable Long id,
                                                       @Valid @RequestBody JobStatusRequest request) {
        return ApiResponse.success(jobService.updateProgressStatus(
                CurrentUser.requireId(), id, request.getStatus()));
    }

    /** 删除岗位（5.5）：逻辑删除，关联分析记录保留；重复删除幂等成功 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        jobService.delete(CurrentUser.requireId(), id);
        return ApiResponse.success();
    }

    /**
     * 发起岗位分析（5.6）：无请求体；同步调用通常 10–30 秒（前端超时按 AI 接口约定设 120 秒）。
     *
     * 描述未变化时直接复用已有结果（不重复调用模型）；描述已变化则生成新版本；
     * 模型超时/不可用 → 50200、输出无法解析 → 50201，失败不写入任何分析记录。
     */
    @PostMapping("/{id}/analyze")
    public ApiResponse<JobAnalysisDetail> analyze(@PathVariable Long id) {
        return ApiResponse.success(jobAnalysisService.analyze(CurrentUser.requireId(), id));
    }

    /** 获取岗位分析（5.7）：返回最新一版成功分析；无结果时 HTTP 200 + analyzed=false */
    @GetMapping("/{id}/analysis")
    public ApiResponse<JobAnalysisView> analysis(@PathVariable Long id) {
        return ApiResponse.success(jobAnalysisService.getLatest(CurrentUser.requireId(), id));
    }

}
