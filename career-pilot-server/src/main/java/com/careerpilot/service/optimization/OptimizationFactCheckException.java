package com.careerpilot.service.optimization;

import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import com.careerpilot.common.BusinessException;

/**
 * 事实校验失败（50201）并携带**完整问题清单**（2026-10-21）。
 *
 * <p>{@link BusinessException} 的文案受 {@code error_message VARCHAR(500)} 限制，
 * 只放得下前 5 项汇总；本异常额外持有结构化 issues，供调用方写入
 * {@code resume_optimization.failure_detail}，详情页据此展示全部问题。
 *
 * <p>issue 的 message 已由校验器脱敏（只含类别、序号与变更索引，不含简历正文、
 * 联系方式或完整术语片段），可直接持久化与展示。
 */
public class OptimizationFactCheckException extends BusinessException {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final List<ResumeOptimizationFactChecker.Issue> issues;

    public OptimizationFactCheckException(String message,
                                         List<ResumeOptimizationFactChecker.Issue> issues) {
        super(50201, 502, message);
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public List<ResumeOptimizationFactChecker.Issue> getIssues() {
        return issues;
    }

    /**
     * 供 {@code resume_optimization.failure_detail} 持久化的 JSON 数组；
     * 没有明细时返回 null（调用方据此跳过写入）。
     */
    public String failureDetailJson() {
        return issues.isEmpty() ? null : MAPPER.writeValueAsString(issues);
    }

}
