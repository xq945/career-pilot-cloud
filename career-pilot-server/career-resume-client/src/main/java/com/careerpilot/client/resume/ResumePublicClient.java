package com.careerpilot.client.resume;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeDetail;
import com.careerpilot.dto.ResumeView;

/**
 * 简历服务的公开 API（与前端接口同形）；单体仅用于列表/详情/删除的远程代理，
 * 实际前端请求由网关直达 career-resume。
 *
 * <p>上传（multipart）与原始文件（二进制响应）不在此接口内——这两个端点只由
 * career-resume 自己的 ResumeController 受理，单体不再承担。
 */
@FeignClient(name = "career-resume", contextId = "resumePublic", path = "/api/resumes")
public interface ResumePublicClient {

    @GetMapping
    PageResult<ResumeView> list(@RequestParam("page") int page, @RequestParam("size") int size);

    @GetMapping("/{id}")
    ResumeDetail detail(@PathVariable("id") Long id);

    @DeleteMapping("/{id}")
    void delete(@PathVariable("id") Long id);
}
