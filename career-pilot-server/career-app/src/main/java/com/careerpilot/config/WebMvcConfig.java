package com.careerpilot.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 静态资源映射（《系统架构设计》7.1，M2 定死）：/uploads/** → storage-root，对外只读。
 *
 * - 仅响应 GET/HEAD（ResourceHttpRequestHandler 天然只读）；
 * - 路径穿越由 Spring 的 PathResourceResolver 兜底（解析结果必须落在映射目录之内）；
 * - 安全放行在 SecurityConfig 中同步声明——**两处缺一不可**（只缺映射为 404，只缺放行为 401）。
 *
 * OSS 模式下本映射依然存在但指向空的本地目录，无副作用（该模式前端拿到的是 OSS 绝对地址）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final FileStorageProperties properties;

    public WebMvcConfig(FileStorageProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 用 Path.toUri() 生成 file: 前缀（Windows 下为 file:///G:/…），
        // 目录已在 FileStorageProperties 构造时创建（toUri 自会带结尾斜杠，此处再兜底一次）
        String location = properties.getRoot().toUri().toString();
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location);
    }

}
