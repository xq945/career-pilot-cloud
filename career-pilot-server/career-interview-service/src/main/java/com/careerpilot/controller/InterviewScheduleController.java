package com.careerpilot.controller;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ModelAttribute;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.InterviewScheduleSaveRequest;
import com.careerpilot.dto.InterviewScheduleStatusRequest;
import com.careerpilot.dto.InterviewScheduleView;
import com.careerpilot.dto.ScheduleParseRequest;
import com.careerpilot.dto.ScheduleParseResponse;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.InterviewScheduleService;

import jakarta.validation.Valid;

/**
 * 面试日程接口（《API接口设计》§9A.1–§9A.7，M17）。
 *
 * 用户身份一律取自认证上下文；归属校验失败一律 404；删除为物理删除（《数据库设计》5.16）。
 */
@RestController
@RequestMapping("/api/interview-schedules")
public class InterviewScheduleController {

    private final InterviewScheduleService interviewScheduleService;

    public InterviewScheduleController(InterviewScheduleService interviewScheduleService) {
        this.interviewScheduleService = interviewScheduleService;
    }

    /** 解析面试邀请（9A.1）：规则优先 + AI 兜底；失败返回失败态（HTTP 200），不落库 */
    @PostMapping("/parse")
    public ApiResponse<ScheduleParseResponse> parse(@Valid @RequestBody ScheduleParseRequest request) {
        return ApiResponse.success(interviewScheduleService.parse(request.getRawText()));
    }

    /** 创建日程（9A.2）：手工填写或确认解析结果（回传 parseSource / confidence） */
    @PostMapping
    public ApiResponse<InterviewScheduleView> create(@Valid @RequestBody InterviewScheduleSaveRequest request) {
        return ApiResponse.success(interviewScheduleService.create(CurrentUser.requireId(), request));
    }

    /** 列表（9A.3）：默认按面试时间升序；status 筛选 + from/to 范围（日历按月拉取用） */
    @GetMapping
    public ApiResponse<PageResult<InterviewScheduleView>> list(
            @Valid @ModelAttribute PageQuery query,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.success(
                interviewScheduleService.list(CurrentUser.requireId(), status, from, to, query));
    }

    /** 详情（9A.4）：不存在或非本人 → 404 */
    @GetMapping("/{id}")
    public ApiResponse<InterviewScheduleView> detail(@PathVariable Long id) {
        return ApiResponse.success(interviewScheduleService.detail(CurrentUser.requireId(), id));
    }

    /** 更新（9A.5，全量）：仅 PENDING 可编辑；时间必须为未来 */
    @PutMapping("/{id}")
    public ApiResponse<InterviewScheduleView> update(@PathVariable Long id,
                                                     @Valid @RequestBody InterviewScheduleSaveRequest request) {
        return ApiResponse.success(interviewScheduleService.update(CurrentUser.requireId(), id, request));
    }

    /** 状态流转（9A.6）：PENDING → COMPLETED / CANCELLED；EXPIRED 不接受用户写入 */
    @PutMapping("/{id}/status")
    public ApiResponse<InterviewScheduleView> updateStatus(@PathVariable Long id,
                                                           @Valid @RequestBody InterviewScheduleStatusRequest request) {
        return ApiResponse.success(
                interviewScheduleService.updateStatus(CurrentUser.requireId(), id, request.getStatus()));
    }

    /** 删除（9A.7）：物理删除 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        interviewScheduleService.delete(CurrentUser.requireId(), id);
        return ApiResponse.success();
    }

}
