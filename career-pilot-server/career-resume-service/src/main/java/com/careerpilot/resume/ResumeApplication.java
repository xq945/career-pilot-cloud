package com.careerpilot.resume;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.LlmProperties;

/**
 * 简历服务启动类（阶段 6b）。
 *
 * <p>本地 Resume 实现与 ResumeAiService 生效（app.resume.mode=local）；模型调用走
 * career-ai（app.ai.mode=remote），因此扫描 ai-core 的 AiCallTemplate/Feign 客户端。
 * 文件存储（career-storage）随域模块传递依赖进入 classpath，由扫描装配 Local/Oss 实现。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.resume",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security",
        "com.careerpilot.ai"
})
@MapperScan("com.careerpilot.mapper")
@EnableFeignClients(basePackages = "com.careerpilot.ai")
@EnableConfigurationProperties({LlmProperties.class, AiCallProperties.class})
public class ResumeApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeApplication.class, args);
    }
}
