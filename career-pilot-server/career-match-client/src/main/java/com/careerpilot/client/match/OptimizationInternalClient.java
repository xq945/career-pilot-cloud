package com.careerpilot.client.match;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.entity.ResumeOptimization;

/**
 * 优化服务的内部端点（跨域读取），由 career-match 的 OptimizationInternalController 实现。
 */
@FeignClient(name = "career-match", contextId = "optimizationInternal", path = "/internal/optimizations")
public interface OptimizationInternalClient {

    /** 归属校验版本（面试来源校验用） */
    @GetMapping("/{id}")
    ResumeOptimization getOwned(@PathVariable("id") Long id);

    /** 展示版本（不过滤 status，供来源名称/版本号装配） */
    @GetMapping("/by-id/{id}")
    ResumeOptimization getById(@PathVariable("id") Long id);

    @PostMapping("/by-ids")
    List<ResumeOptimization> listByIds(@RequestBody List<Long> ids);

    @GetMapping("/recent")
    List<ResumeOptimization> recent(@RequestParam("limit") int limit);
}
