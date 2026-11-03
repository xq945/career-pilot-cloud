package com.careerpilot.config;

import java.io.IOException;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.security.JwtAuthenticationFilter;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security 配置，依据《系统架构设计》7 节与《API接口设计》2 节。
 *
 * - 无状态：不使用 Session，认证完全依赖 JWT；
 * - 公开路径为注册、登录、图形验证码与 /uploads/**（公开文件，见 7.1 节），其余一律要求认证；
 * - 未认证 / 无权限统一返回与业务接口一致的响应结构；
 * - CORS 来源从配置读取（不用通配符），生产同源部署时应关闭。
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    private final ObjectMapper objectMapper;

    @Value("${app.cors.allowed-origins:http://localhost:5173}")
    private String allowedOrigins;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // 认证靠 Authorization 头而非 Cookie，不使用 CSRF Token
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // 图形验证码（M18）在**登录前**获取，此时必然未认证，故一并放行
                        .requestMatchers("/api/auth/register", "/api/auth/login", "/api/auth/captcha")
                        .permitAll()
                        // 头像等公开文件（《系统架构设计》7.1）：<img> 标签不携带 Authorization 头，
                        // 且地址含不可枚举的 UUID，故只读开放；资源映射见 WebMvcConfig（两处缺一不可）
                        .requestMatchers("/uploads/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((request, response, ex1) ->
                                writeError(response, HttpServletResponse.SC_UNAUTHORIZED,
                                        ApiResponse.error(40100, "未登录或登录已过期")))
                        // 授权失败（已认证但无权限）：按项目定死规则统一为 404/40400（R03）——
                        // 40300 专指账号状态受限（当前仅登录时账号被禁用），资源级越权一律 404，
                        // 不区分"不存在"与"无权访问"，避免暴露资源存在性（《API接口设计》§1/A01）
                        .accessDeniedHandler((request, response, ex1) ->
                                writeError(response, HttpServletResponse.SC_NOT_FOUND,
                                        ApiResponse.error(40400, "资源不存在"))))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of(allowedOrigins.split(",")));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        // 允许携带预检结果缓存（《系统架构设计》7.2）
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    private void writeError(HttpServletResponse response, int status, ApiResponse<Void> body)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

}
