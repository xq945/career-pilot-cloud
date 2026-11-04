package com.careerpilot.client.match;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.dto.MatchDetail;
import com.careerpilot.dto.MatchInternalCreateRequest;
import com.careerpilot.dto.MatchStats;
import com.careerpilot.entity.MatchRecord;

/**
 * 匹配服务的内部端点（跨域读取/创建），由 career-match 的 MatchInternalController 实现。
 */
@FeignClient(name = "career-match", contextId = "matchInternal", path = "/internal/matches")
public interface MatchInternalClient {

    /** 创建匹配：未传分析 ID 时由服务端自动补齐（Agent 入口） */
    @PostMapping
    MatchDetail create(@RequestBody MatchInternalCreateRequest request);

    @GetMapping("/{id}")
    MatchRecord getOwned(@PathVariable("id") Long id);

    @GetMapping("/latest")
    MatchRecord latest(@RequestParam("resumeId") Long resumeId, @RequestParam("jobId") Long jobId);

    @GetMapping("/latest-owned")
    MatchRecord latestOwned();

    @GetMapping("/stats")
    MatchStats stats();

    @GetMapping("/recent")
    List<MatchRecord> recent(@RequestParam("limit") int limit);
}
