package com.careerpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.careerpilot.config.AgentProperties;
import com.careerpilot.config.AiCallProperties;
import com.careerpilot.config.CaptchaProperties;
import com.careerpilot.config.LlmProperties;
import com.careerpilot.config.MatchScoringProperties;
import com.careerpilot.config.OptimizationAsyncProperties;

/**
 * 智能求职辅助平台后端服务启动类。
 *
 * 排除 {@code UserDetailsServiceAutoConfiguration}：本项目认证完全基于 JWT 过滤器，
 * 不使用 Spring Security 的 UserDetailsService。若不排除，启动时会自动装配一个内存用户
 * 并打印 "Using generated security password" 警告，既无用处又容易造成误解。
 *
 * 评分配置与智能体配置经 {@code @EnableConfigurationProperties} 显式注册走配置绑定：
 * @ConfigurationProperties 的构造器绑定**不能**与 @Component 组件扫描混用
 * （否则构造参数会被当作待注入 bean 而启动失败）。
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties({MatchScoringProperties.class, AgentProperties.class,
        LlmProperties.class, AiCallProperties.class, OptimizationAsyncProperties.class,
        CaptchaProperties.class})
public class CareerPilotApplication {

    public static void main(String[] args) {
        SpringApplication.run(CareerPilotApplication.class, args);
    }

}
