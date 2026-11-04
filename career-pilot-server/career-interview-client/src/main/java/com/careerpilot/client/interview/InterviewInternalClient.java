package com.careerpilot.client.interview;

import java.util.List;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.entity.InterviewSession;

/**
 * 面试服务的内部端点（跨域只读），由 career-interview 的 InterviewInternalController 实现。
 */
@FeignClient(name = "career-interview", contextId = "interviewInternal", path = "/internal/interviews")
public interface InterviewInternalClient {

    @GetMapping("/count")
    long countOwned();

    @GetMapping("/count-by-status")
    long countOwnedByStatus(@RequestParam("status") int status);

    @GetMapping("/recent")
    List<InterviewSession> recent(@RequestParam("limit") int limit);

    @GetMapping("/owned/{id}")
    InterviewSession getOwned(@PathVariable("id") Long id);
}
