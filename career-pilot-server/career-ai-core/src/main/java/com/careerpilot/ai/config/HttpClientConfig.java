package com.careerpilot.ai.config;

import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;

/**
 * 模型调用 HTTP 客户端配置（阶段 5 修复）。
 *
 * <p>DeepSeek 在请求携带 {@code Accept-Encoding: gzip} 时会压缩响应；当前 Spring Boot 4.0.9
 * 的 RestClient 组合在工具调用的大响应上未能正确解压，Jackson 报
 * {@code Unexpected end-of-input}。统一改为 {@code identity}，让服务端返回未压缩 JSON。
 *
 * <p>该 Customizer 作用于 Spring Boot 自动配置的 {@code RestClient.Builder}：
 * 平台默认 DeepSeek 与用户级 DeepSeek 都从这里构建。
 */
@Configuration
public class HttpClientConfig {

    @Bean
    RestClientCustomizer identityAcceptEncodingCustomizer() {
        return builder -> builder.defaultHeader(HttpHeaders.ACCEPT_ENCODING, "identity");
    }
}
