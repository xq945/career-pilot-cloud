package com.careerpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.MatchService;

import jakarta.validation.Valid;

/**
 * 匹配接口（《API接口设计》6.1–6.3）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId（《系统架构设计》4.1）。
 */
@RestController
@RequestMapping("/api/matches")
public class MatchController {

    private final MatchService matchService;

    public MatchController(MatchService matchService) {
        this.matchService = matchService;
    }

    /**
     * 创建匹配分析（6.1）：**每次调用创建一条新记录**（保留历史，便于对比简历修改效果）；
     * 缺分析或岗位分析过期 → 400；模型超时/输出异常 → 502，失败不入库。
     *
     * 同步调用通常耗时 5–20 秒（评分在本地计算，模型仅生成解释与建议）。
     */
    @PostMapping
    public ApiResponse<MatchDetail> create(@Valid @RequestBody MatchCreateRequest request) {
        return ApiResponse.success(matchService.create(CurrentUser.requireId(), request));
    }

    /** 匹配记录列表（6.2）：created_at 倒序分页；内联资源名称与所用分析版本 */
    @GetMapping
    public ApiResponse<PageResult<MatchView>> list(@Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(matchService.list(CurrentUser.requireId(), query));
    }

    /** 匹配报告详情（6.3）：响应结构与创建接口一致；不存在或非本人 → 404 */
    @GetMapping("/{id}")
    public ApiResponse<MatchDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(matchService.detail(CurrentUser.requireId(), id));
    }

}
