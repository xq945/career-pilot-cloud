package com.careerpilot.auth;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.careerpilot.config.CaptchaProperties;

/**
 * 认证服务启动类（阶段 4）。
 *
 * <p>扫描范围刻意限定在 auth 自身、service（含 storage）、controller、config 与 security，
 * 不扫描 common/infrastructure——避免把 Redis Stream 模板等单体组件带进认证服务。
 */
@SpringBootApplication(scanBasePackages = {
        "com.careerpilot.auth",
        "com.careerpilot.service",
        "com.careerpilot.controller",
        "com.careerpilot.config",
        "com.careerpilot.security"
})
@MapperScan("com.careerpilot.mapper")
@EnableScheduling
@EnableConfigurationProperties(CaptchaProperties.class)
public class AuthApplication {

    public static void main(String[] args) {
        SpringApplication.run(AuthApplication.class, args);
    }
}
