package com.careerpilot.client.match;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.MatchCreateRequest;
import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchView;

/**
 * 匹配服务的公开 API（与前端接口同形，响应统一包在 {@link ApiResponse} 中）；
 * 单体仅用于远程代理，实际前端请求由网关直达 career-match。
 */
@FeignClient(name = "career-match", contextId = "matchPublic", path = "/api/matches")
public interface MatchPublicClient {

    @PostMapping
    ApiResponse<MatchDetail> create(@RequestBody MatchCreateRequest request);

    @GetMapping
    ApiResponse<PageResult<MatchView>> list(@RequestParam("page") int page,
                                            @RequestParam("size") int size);

    @GetMapping("/{id}")
    ApiResponse<MatchDetail> detail(@PathVariable("id") Long id);
}
