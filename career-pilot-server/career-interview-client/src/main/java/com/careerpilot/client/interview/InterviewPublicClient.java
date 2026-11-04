package com.careerpilot.client.interview;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.AnswerEvaluationResponse;
import com.careerpilot.dto.AnswerSubmitRequest;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewDetail;
import com.careerpilot.dto.InterviewReport;
import com.careerpilot.dto.InterviewView;

/**
 * 面试服务的公开端点（/api/interviews）。身份由 Feign 拦截器按目标服务签发内部令牌，
 * 服务端从认证上下文取 userId（不接受请求体传入）。
 *
 * <p>公开接口统一返回 {@link ApiResponse} 信封，调用方必须解包（阶段 7 的教训：
 * 直接按裸 DTO 解码会抛 {@code DecodeException}）。
 */
@FeignClient(name = "career-interview", contextId = "interviewPublic", path = "/api/interviews")
public interface InterviewPublicClient {

    @PostMapping
    ApiResponse<InterviewCreateResponse> create(@RequestBody InterviewCreateRequest request);

    @PostMapping("/{id}/questions/{questionId}/answer")
    ApiResponse<AnswerEvaluationResponse> answer(@PathVariable("id") Long id,
                                                 @PathVariable("questionId") Long questionId,
                                                 @RequestBody AnswerSubmitRequest request);

    @GetMapping
    ApiResponse<PageResult<InterviewView>> list(@RequestParam("page") long page,
                                                @RequestParam("size") long size);

    @GetMapping("/{id}")
    ApiResponse<InterviewDetail> detail(@PathVariable("id") Long id);

    @GetMapping("/{id}/report")
    ApiResponse<InterviewReport> report(@PathVariable("id") Long id);

    @PostMapping("/{id}/summary/regenerate")
    ApiResponse<InterviewReport> regenerateSummary(@PathVariable("id") Long id);
}
