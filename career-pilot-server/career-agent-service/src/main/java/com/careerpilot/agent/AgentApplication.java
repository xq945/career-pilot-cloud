package com.careerpilot.agent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.openfeign.EnableFeignClients;

import com.careerpilot.config.AgentProperties;
import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.LlmProperties;
import com.careerpilot.config.MatchScoringProperties;

/**
 * 求职智能体服务启动类（阶段 9，8086）。
 *
 * <p>Agent 会话与 Tool Calling 在本服务执行（app.agent.mode=local）；四个工具的业务实现
 * 全部经 Feign 访问简历/岗位/匹配/面试服务（app.*.mode=remote）。
 *
 * <p>模型调用仍是**本地 Tool Calling**（方案 §4.3 保留 {@code @Tool}/预算/凭证体系），
 * 因此需要平台 DeepSeek 配置与 LLM 加密主密钥；用户级 Provider 通过 cp_agent 的
 * 只读视图读取（过渡方案，见迁移脚本）。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.agent",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security",
        "com.careerpilot.ai",
        "com.careerpilot.client"
})
@MapperScan("com.careerpilot.mapper")
@EnableFeignClients(basePackages = {"com.careerpilot.ai", "com.careerpilot.client"})
@EnableConfigurationProperties({AgentProperties.class, LlmProperties.class,
        AiCallProperties.class,
        // 扫描 com.careerpilot.service 会装配匹配域的 MatchScoringService（共享包 Bean 泄漏）
        MatchScoringProperties.class})
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
