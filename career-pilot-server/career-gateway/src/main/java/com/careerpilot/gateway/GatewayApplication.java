package com.careerpilot.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.careerpilot.security.InternalTokenService;
import com.careerpilot.security.JwtTokenProvider;
import com.careerpilot.security.TokenBlacklistService;

/**
 * 网关启动类。
 *
 * <p>只扫描 {@code com.careerpilot.gateway}，并通过 {@code @Import} 显式引入
 * 两个无 Servlet 依赖的令牌组件——避免把 {@code career-security} 中的
 * Servlet 过滤器带进 WebFlux 网关。
 */
@SpringBootApplication(scanBasePackages = "com.careerpilot.gateway")
@Import({JwtTokenProvider.class, InternalTokenService.class, TokenBlacklistService.class})
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
