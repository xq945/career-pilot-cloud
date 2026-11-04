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
import com.careerpilot.dto.AnswerEvaluationResponse;
import com.careerpilot.dto.AnswerSubmitRequest;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewDetail;
import com.careerpilot.dto.InterviewReport;
import com.careerpilot.dto.InterviewView;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.InterviewService;

import jakarta.validation.Valid;

/**
 * 面试接口（《API接口设计》§7）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId（《系统架构设计》4.1）；
 * 会话/题目的归属校验在 Service 层完成，失败一律 404。
 */
@RestController
@RequestMapping("/api/interviews")
public class InterviewController {

    private final InterviewService interviewService;

    public InterviewController(InterviewService interviewService) {
        this.interviewService = interviewService;
    }

    /**
     * 创建面试会话（§7.1）：题目随会话一次性生成（同步调用通常 10–40 秒）。
     *
     * 生成失败（50200/50201）整体回滚，**不产生空会话**，可直接重试。
     */
    @PostMapping
    public ApiResponse<InterviewCreateResponse> create(@Valid @RequestBody InterviewCreateRequest request) {
        return ApiResponse.success(interviewService.create(CurrentUser.requireId(), request));
    }

    /**
     * 提交回答并评价（§7.2）：同步调用通常 10–30 秒；评价失败**整体不入库**
     * （50200/50201），前端应保留输入供重试。重复提交覆盖上一次评价，得分以最后一次为准。
     *
     * 本次提交使会话完成时，服务端会额外生成总评（§7.4 V2.5）——总评失败不影响本次提交成功。
     */
    @PostMapping("/{id}/questions/{questionId}/answer")
    public ApiResponse<AnswerEvaluationResponse> answer(@PathVariable Long id,
                                                        @PathVariable Long questionId,
                                                        @Valid @RequestBody AnswerSubmitRequest request) {
        return ApiResponse.success(interviewService.submitAnswer(
                CurrentUser.requireId(), id, questionId, request.getAnswer()));
    }

    /** 面试列表（§7.3）：倒序分页；带 generatedCount / answeredCount 与内联岗位名称 */
    @GetMapping
    public ApiResponse<PageResult<InterviewView>> list(@Valid @ModelAttribute PageQuery query) {
        return ApiResponse.success(interviewService.list(CurrentUser.requireId(), query));
    }

    /** 面试详情（§7.3）：会话信息 + 全部题目（含已提交的回答与评价），按 sortOrder 升序 */
    @GetMapping("/{id}")
    public ApiResponse<InterviewDetail> detail(@PathVariable Long id) {
        return ApiResponse.success(interviewService.detail(CurrentUser.requireId(), id));
    }

    /**
     * 面试报告（§7.4）：总分与总评**均直接返回已写入的值**，不做读取时惰性计算；
     * 未完成时 `completed = false`、`totalScore` 为 null、总评字段为 null。
     */
    @GetMapping("/{id}/report")
    public ApiResponse<InterviewReport> report(@PathVariable Long id) {
        return ApiResponse.success(interviewService.report(CurrentUser.requireId(), id));
    }

    /**
     * 重新生成总评（§7.5，M15）：仅已完成会话可调用；使用**创建会话时钉住的模型服务**
     * 重新生成，成功覆盖总评并标注来源 `AI`；**失败（502）时原总评保持不变**；
     * 响应与报告接口结构一致。
     */
    @PostMapping("/{id}/summary/regenerate")
    public ApiResponse<InterviewReport> regenerateSummary(@PathVariable Long id) {
        return ApiResponse.success(interviewService.regenerateSummary(CurrentUser.requireId(), id));
    }

}
