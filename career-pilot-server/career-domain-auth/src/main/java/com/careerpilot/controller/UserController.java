package com.careerpilot.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.careerpilot.common.ApiResponse;
import com.careerpilot.dto.UpdateProfileRequest;
import com.careerpilot.dto.UserProfile;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.UserService;

import jakarta.validation.Valid;

/**
 * 用户接口（《API接口设计》3.3 / 3.4 / 3.6 / 3.7）。
 *
 * 用户身份一律取自认证上下文，不接受前端传入的 userId。
 */
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping("/profile")
    public ApiResponse<UserProfile> profile() {
        return ApiResponse.success(userService.getProfile(CurrentUser.requireId()));
    }

    @PutMapping("/profile")
    public ApiResponse<UserProfile> updateProfile(@Valid @RequestBody UpdateProfileRequest request) {
        return ApiResponse.success(userService.updateProfile(CurrentUser.requireId(), request));
    }

    /**
     * 上传头像（3.6）。
     *
     * required = false 是为了让「缺 file 字段」走到业务层判空并返回 40000，
     * 而不是抛 MissingServletRequestPartException 落到 50000。
     */
    @PostMapping("/avatar")
    public ApiResponse<UserProfile> uploadAvatar(
            @RequestPart(value = "file", required = false) MultipartFile file) {
        return ApiResponse.success(userService.updateAvatar(CurrentUser.requireId(), file));
    }

    /** 移除头像（3.7，幂等） */
    @DeleteMapping("/avatar")
    public ApiResponse<UserProfile> removeAvatar() {
        return ApiResponse.success(userService.removeAvatar(CurrentUser.requireId()));
    }

}
