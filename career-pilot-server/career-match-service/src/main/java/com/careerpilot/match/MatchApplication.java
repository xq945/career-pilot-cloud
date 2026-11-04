package com.careerpilot.match;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.LlmProperties;
import com.careerpilot.config.MatchScoringProperties;
import com.careerpilot.config.OptimizationAsyncProperties;

/**
 * 匹配与优化服务启动类（阶段 7）。
 *
 * <p>本地 Match/Optimization 实现生效（app.match.mode=local）；简历、岗位、模型均走远程
 * （app.resume.mode=remote、app.job.mode=remote、app.ai.mode=remote），
 * 因此扫描客户端模块与 ai-core 的 Feign 客户端。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.match",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security",
        "com.careerpilot.ai",
        "com.careerpilot.client",
        "com.careerpilot.infrastructure"
})
@MapperScan("com.careerpilot.mapper")
@EnableFeignClients(basePackages = {"com.careerpilot.ai", "com.careerpilot.client"})
@EnableConfigurationProperties({MatchScoringProperties.class, OptimizationAsyncProperties.class,
        LlmProperties.class, AiCallProperties.class})
@EnableScheduling
public class MatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(MatchApplication.class, args);
    }
}
