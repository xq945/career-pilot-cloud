package com.careerpilot.gateway;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.security.InternalTokenService;
import com.careerpilot.security.JwtTokenProvider;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

/**
 * 网关统一认证（阶段 3）：
 *
 * <ol>
 *   <li>先剥离客户端可能伪造的 {@code X-Internal-Token}/{@code X-User-Id}/{@code X-Username}
 *       （白名单请求同样剥离）；</li>
 *   <li>白名单与 OPTIONS 直接转发；</li>
 *   <li>其余请求校验 {@code Authorization: Bearer <JWT>}；</li>
 *   <li>验签通过后签发 5 分钟内部令牌，注入 {@code X-Internal-Token} 与 {@code X-Trace-Id}。</li>
 * </ol>
 */
@Component
public class GatewayAuthFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthFilter.class);

    private static final List<String> WHITELIST = List.of(
            "/api/auth/register", "/api/auth/login", "/api/auth/captcha");

    private static final String BEARER_PREFIX = "Bearer ";

    private static final String TRACE_HEADER = "X-Trace-Id";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JwtTokenProvider jwtTokenProvider;

    private final InternalTokenService internalTokenService;

    public GatewayAuthFilter(JwtTokenProvider jwtTokenProvider,
                             InternalTokenService internalTokenService) {
        this.jwtTokenProvider = jwtTokenProvider;
        this.internalTokenService = internalTokenService;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 无论是否白名单，都先剥离客户端自带的身份头，防止直连/伪造
        ServerHttpRequest clean = request.mutate().headers(h -> {
            h.remove(InternalTokenService.HEADER);
            h.remove("X-User-Id");
            h.remove("X-Username");
        }).build();
        ServerWebExchange cleanExchange = exchange.mutate().request(clean).build();

        if (HttpMethod.OPTIONS.equals(request.getMethod()) || isWhitelisted(path)) {
            return chain.filter(cleanExchange);
        }

        String authorization = request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return unauthorized(exchange);
        }
        try {
            Claims claims = jwtTokenProvider.parse(authorization.substring(BEARER_PREFIX.length()).trim());
            Long userId = claims.get("userId", Number.class).longValue();
            String username = claims.getSubject();
            String internalToken = internalTokenService.issue(userId, username);
            String traceId = request.getHeaders().getFirst(TRACE_HEADER);
            if (traceId == null || traceId.isBlank()) {
                traceId = UUID.randomUUID().toString().replace("-", "");
            }
            String finalTraceId = traceId;
            ServerHttpRequest authorized = clean.mutate().headers(h -> {
                h.set(InternalTokenService.HEADER, internalToken);
                h.set(TRACE_HEADER, finalTraceId);
            }).build();
            return chain.filter(exchange.mutate().request(authorized).build());
        }
        catch (JwtException | IllegalArgumentException | NullPointerException ex) {
            log.debug("网关 JWT 校验失败：{}", ex.getMessage());
            return unauthorized(exchange);
        }
    }

    private boolean isWhitelisted(String path) {
        return WHITELIST.contains(path) || path.startsWith("/uploads/");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body;
        try {
            body = JSON.writeValueAsBytes(ApiResponse.error(40100, "未登录或登录已过期"));
        }
        catch (Exception ex) {
            body = "{\"code\":40100,\"message\":\"未登录或登录已过期\"}"
                    .getBytes(StandardCharsets.UTF_8);
        }
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
