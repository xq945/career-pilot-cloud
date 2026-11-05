package com.careerpilot.security;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * traceId 传播（阶段 10）：接收网关注入的 {@code X-Trace-Id}，写入 MDC 与响应头，
 * 供日志 pattern（{@code %X{traceId}}）与 Feign 拦截器在整条调用链复用。
 *
 * <p>只接受长度 ≤64 的安全字符（字母/数字/_/-）；缺失或非法时由本服务生成。
 * 直连服务（绕过网关）的请求也会得到 traceId，日志同样可关联。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Trace-Id";

    public static final String MDC_KEY = "traceId";

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = sanitize(request.getHeader(HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        response.setHeader(HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        }
        finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static String sanitize(String header) {
        if (header == null) {
            return null;
        }
        String trimmed = header.trim();
        return SAFE.matcher(trimmed).matches() ? trimmed : null;
    }
}
