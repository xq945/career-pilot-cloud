package com.careerpilot.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.dto.CaptchaResponse;
import com.careerpilot.dto.LoginRequest;
import com.careerpilot.dto.LoginResponse;
import com.careerpilot.dto.RegisterRequest;
import com.careerpilot.dto.UserProfile;
import com.careerpilot.service.AuthService;
import com.careerpilot.service.CaptchaService;

import jakarta.validation.Valid;

/**
 * 认证接口（《API接口设计》3.1、3.2、3.3、3.5）。注册、登录与图形验证码为公开接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    private final CaptchaService captchaService;

    public AuthController(AuthService authService, CaptchaService captchaService) {
        this.authService = authService;
        this.captchaService = captchaService;
    }

    @PostMapping("/register")
    public ApiResponse<UserProfile> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 获取图形验证码（M18，《API接口设计》3.3）。公开接口，登录前调用。
     *
     * <p>刷新场景可传 {@code replace}：生成新码的同时**立即作废**旧码，
     * 满足"验证码刷新后旧验证码不可继续使用"。
     */
    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> captcha(@RequestParam(required = false) String replace) {
        return ApiResponse.success(captchaService.generate(replace));
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        // 验证码先于账号密码校验：不通过时根本不进入 AuthService，JWT 自然不会签发（M18）
        captchaService.verify(request.getCaptchaId(), request.getCaptchaCode());
        return ApiResponse.success(authService.login(request));
    }

    /**
     * 退出登录。
     *
     * 服务端不实现 Token 撤销机制，该接口仅表示客户端应清除本地 Token
     * （《API接口设计》3.5），不得声称服务端已使 Token 失效。
     */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        return ApiResponse.success();
    }

}
