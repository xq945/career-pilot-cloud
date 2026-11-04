package com.careerpilot.interview;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.InterviewSummaryProperties;
import com.careerpilot.config.LlmProperties;
import com.careerpilot.config.MatchScoringProperties;

/**
 * 面试服务启动类（阶段 8，8085）。
 *
 * <p>本地面试/日程实现生效（app.interview.mode=local）；简历、岗位、匹配、模型均走远程
 * （app.resume/job/match.mode=remote、app.ai.mode=remote），因此扫描客户端模块与
 * ai-core 的 Feign 客户端。总评默认异步：outbox 投递器与 RabbitMQ 消费者随本服务启动。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.interview",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security",
        "com.careerpilot.ai",
        "com.careerpilot.client"
})
@MapperScan("com.careerpilot.mapper")
@EnableFeignClients(basePackages = {"com.careerpilot.ai", "com.careerpilot.client"})
@EnableConfigurationProperties({LlmProperties.class, AiCallProperties.class,
        InterviewSummaryProperties.class,
        // 面试服务不执行匹配，但扫描 com.careerpilot.service 会装配 MatchScoringService；
        // 注册其 fail-fast 配置（权重见 application.yml 的 app.match.weights 占位）
        MatchScoringProperties.class})
@EnableScheduling
public class InterviewApplication {

    public static void main(String[] args) {
        SpringApplication.run(InterviewApplication.class, args);
    }
}
