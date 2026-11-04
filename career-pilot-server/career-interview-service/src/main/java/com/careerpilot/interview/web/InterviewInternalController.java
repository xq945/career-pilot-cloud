package com.careerpilot.interview.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.entity.InterviewSession;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.InterviewService;

/**
 * 面试服务的内部 API（跨域只读，阶段 8）。
 *
 * <p>当前唯一的调用方是单体的 Dashboard 聚合；身份来自网关注入的内部令牌，
 * 属主过滤仍由本地服务完成（CurrentUser 取不到时直接 401）。
 */
@RestController
@RequestMapping("/internal/interviews")
public class InterviewInternalController {

    private final InterviewService interviewService;

    public InterviewInternalController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    @GetMapping("/count")
    public long countOwned() {
        return interviewService.countOwnedSessions(CurrentUser.requireId());
    }

    @GetMapping("/count-by-status")
    public long countOwnedByStatus(@RequestParam("status") int status) {
        return interviewService.countOwnedSessionsByStatus(CurrentUser.requireId(), status);
    }

    @GetMapping("/recent")
    public List<InterviewSession> recent(@RequestParam("limit") int limit) {
        return interviewService.recentSessions(CurrentUser.requireId(), limit);
    }

    @GetMapping("/owned/{id}")
    public InterviewSession getOwned(@PathVariable("id") Long id) {
        return interviewService.getOwnedSession(CurrentUser.requireId(), id);
    }
}
