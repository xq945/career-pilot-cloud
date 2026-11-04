package com.careerpilot.client.job;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.common.PageResult;
import com.careerpilot.dto.JobDetail;
import com.careerpilot.dto.JobSaveRequest;
import com.careerpilot.dto.JobStatusRequest;
import com.careerpilot.dto.JobView;

/**
 * 岗位服务的公开 API（与前端接口同形）；单体仅用于 CRUD 的远程代理，实际前端请求由网关直达 career-job。
 */
@FeignClient(name = "career-job", contextId = "jobPublic", path = "/api/jobs")
public interface JobPublicClient {

    @PostMapping
    JobDetail create(@RequestBody JobSaveRequest request);

    @GetMapping
    PageResult<JobView> list(@RequestParam("page") int page, @RequestParam("size") int size);

    @GetMapping("/{id}")
    JobDetail detail(@PathVariable("id") Long id);

    @PutMapping("/{id}")
    JobDetail update(@PathVariable("id") Long id, @RequestBody JobSaveRequest request);

    @PutMapping("/{id}/status")
    JobDetail updateStatus(@PathVariable("id") Long id, @RequestBody JobStatusRequest request);

    @DeleteMapping("/{id}")
    void delete(@PathVariable("id") Long id);
}
