package com.careerpilot.security;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import feign.RequestInterceptor;
import feign.RequestTemplate;

/**
 * Feign 调用的 traceId 透传（阶段 10）：把当前请求 MDC 中的 traceId 写入
 * {@code X-Trace-Id}，下游由 {@link TraceIdFilter} 接收并继续传递。
 *
 * <p>异步任务/消费者没有 MDC 时不下发该头，下游会生成自己的 traceId，不阻断调用。
 */
@Component
public class TraceIdFeignInterceptor implements RequestInterceptor {

    @Override
    public void apply(RequestTemplate template) {
        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId != null && !traceId.isBlank()) {
            template.header(TraceIdFilter.HEADER, traceId);
        }
    }
}
