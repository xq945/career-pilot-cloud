package com.careerpilot.job;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.LlmProperties;

/**
 * 岗位服务启动类（阶段 6）。
 *
 * <p>本地 Job 实现与 JobAiService 生效（app.job.mode=local）；模型调用走 career-ai
 * （app.ai.mode=remote），因此扫描 ai-core 的 AiCallTemplate/Feign 客户端。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.job",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security",
        "com.careerpilot.ai"
})
@MapperScan("com.careerpilot.mapper")
@EnableFeignClients(basePackages = "com.careerpilot.ai")
@EnableConfigurationProperties({LlmProperties.class, AiCallProperties.class})
public class JobApplication {

    public static void main(String[] args) {
        SpringApplication.run(JobApplication.class, args);
    }
}
