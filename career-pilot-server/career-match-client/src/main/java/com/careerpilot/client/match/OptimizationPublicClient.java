package com.careerpilot.client.match;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.ResumeOptimizationContentRequest;
import com.careerpilot.dto.ResumeOptimizationCreateRequest;
import com.careerpilot.dto.ResumeOptimizationDetail;
import com.careerpilot.dto.ResumeOptimizationView;

/**
 * 优化服务的公开 API（与前端接口同形，响应统一包在 {@link ApiResponse} 中）。
 *
 * <p>DOCX 导出是二进制响应，不在本接口内——它只由 career-match 的控制器受理。
 */
@FeignClient(name = "career-match", contextId = "optimizationPublic", path = "/api/optimizations")
public interface OptimizationPublicClient {

    @PostMapping
    ApiResponse<ResumeOptimizationDetail> create(@RequestBody ResumeOptimizationCreateRequest request);

    @GetMapping
    ApiResponse<PageResult<ResumeOptimizationView>> list(@RequestParam("page") int page,
                                                         @RequestParam("size") int size);

    @GetMapping("/{id}")
    ApiResponse<ResumeOptimizationDetail> detail(@PathVariable("id") Long id);

    @PutMapping("/{id}/content")
    ApiResponse<ResumeOptimizationDetail> saveContent(@PathVariable("id") Long id,
                                                      @RequestBody ResumeOptimizationContentRequest request);

    @PostMapping("/{id}/evaluate")
    ApiResponse<ResumeOptimizationDetail> evaluate(@PathVariable("id") Long id);

    @PostMapping("/{id}/retry")
    ApiResponse<ResumeOptimizationDetail> retry(@PathVariable("id") Long id);
}
