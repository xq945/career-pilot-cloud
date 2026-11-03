package com.careerpilot.security;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 认证过滤器：解析 Authorization 头，校验通过则写入 SecurityContext。
 *
 * 校验失败时不在此处直接返回错误，而是保持未认证状态 —— 交由认证入口点统一返回
 * 401，保证所有未认证场景的响应结构一致。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String HEADER = "Authorization";

    private static final String PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;

    private final InternalTokenService internalTokenService;

    public JwtAuthenticationFilter(JwtTokenProvider tokenProvider,
                                   InternalTokenService internalTokenService) {
        this.tokenProvider = tokenProvider;
        this.internalTokenService = internalTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // 网关转发的内部令牌优先：只信任其签名/scope/aud，忽略客户端自带的 Authorization。
        // 若内部令牌无效，不回退到 Bearer —— 避免"伪造内部头 + 合法用户令牌"的绕过。
        String internalToken = request.getHeader(InternalTokenService.HEADER);
        if (internalToken != null && !internalToken.isBlank()) {
            try {
                Claims claims = internalTokenService.parse(internalToken);
                Long userId = Long.valueOf(claims.getSubject());
                String username = claims.get("username", String.class);
                setAuthentication(request, userId, username);
            }
            catch (JwtException | IllegalArgumentException ex) {
                log.debug("内部令牌校验失败：{}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
            filterChain.doFilter(request, response);
            return;
        }

        String header = request.getHeader(HEADER);
        if (header != null && header.startsWith(PREFIX)) {
            String token = header.substring(PREFIX.length()).trim();
            try {
                Claims claims = tokenProvider.parse(token);
                Long userId = claims.get("userId", Number.class).longValue();
                String username = claims.getSubject();
                setAuthentication(request, userId, username);
            }
            catch (JwtException | IllegalArgumentException ex) {
                log.debug("JWT 校验失败：{}", ex.getMessage());
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }

    private static void setAuthentication(HttpServletRequest request, Long userId, String username) {
        var authentication = new UsernamePasswordAuthenticationToken(
                new AuthenticatedUser(userId, username),
                null,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

}
