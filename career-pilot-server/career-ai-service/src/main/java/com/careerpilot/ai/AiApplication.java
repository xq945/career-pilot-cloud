package com.careerpilot.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.LlmProperties;

/**
 * AI 服务启动类（阶段 5）。
 *
 * <p>扫描 ai-core（Provider/Registry/AiCallTemplate）、LlmProviderService、迁移过来的
 * LlmProviderController、配置与安全组件；不扫描 business 域的 service。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.ai",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security"
})
@MapperScan("com.careerpilot.mapper")
@EnableConfigurationProperties({LlmProperties.class, AiCallProperties.class})
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
