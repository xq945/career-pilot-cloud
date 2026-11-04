package com.careerpilot.match.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.ResumeOptimizationService;

/**
 * 优化服务的内部 API（跨域读取）。
 *
 * <p>身份来自网关注入的内部令牌；归属校验由本地服务完成。
 */
@RestController
@RequestMapping("/internal/optimizations")
public class OptimizationInternalController {

    private final ResumeOptimizationService optimizationService;

    public OptimizationInternalController(ResumeOptimizationService optimizationService) {
        this.optimizationService = optimizationService;
    }

    @GetMapping("/{id}")
    public ResumeOptimization getOwned(@PathVariable("id") Long id) {
        return optimizationService.getOwned(CurrentUser.requireId(), id);
    }

    @GetMapping("/by-id/{id}")
    public ResumeOptimization getById(@PathVariable("id") Long id) {
        return optimizationService.getOptimizationById(id);
    }

    @PostMapping("/by-ids")
    public List<ResumeOptimization> listByIds(@RequestBody List<Long> ids) {
        return optimizationService.listOptimizationsByIds(ids);
    }

    @GetMapping("/recent")
    public List<ResumeOptimization> recent(@RequestParam("limit") int limit) {
        return optimizationService.recentSuccessOptimizations(CurrentUser.requireId(), limit);
    }
}
