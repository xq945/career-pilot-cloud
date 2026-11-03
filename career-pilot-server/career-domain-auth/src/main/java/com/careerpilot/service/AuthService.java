package com.careerpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.LoginRequest;
import com.careerpilot.dto.LoginResponse;
import com.careerpilot.dto.RegisterRequest;
import com.careerpilot.dto.UserProfile;
import com.careerpilot.entity.SysUser;
import com.careerpilot.mapper.SysUserMapper;
import com.careerpilot.security.JwtTokenProvider;
import com.careerpilot.security.TokenBlacklistService;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;

/**
 * 认证服务（《API接口设计》3 节）：注册、登录与 Token 签发。
 *
 * 用户资料的读写已迁至 UserService；本类经它装配用户视图
 * （register/login 响应中的 avatar 需由存储层转换为访问地址）。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final int STATUS_ENABLED = 1;

    private static final String BEARER_PREFIX = "Bearer ";

    private final SysUserMapper userMapper;

    private final PasswordEncoder passwordEncoder;

    private final JwtTokenProvider tokenProvider;

    private final UserService userService;

    private final TokenBlacklistService tokenBlacklistService;

    public AuthService(SysUserMapper userMapper, PasswordEncoder passwordEncoder,
                       JwtTokenProvider tokenProvider, UserService userService,
                       TokenBlacklistService tokenBlacklistService) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
        this.tokenProvider = tokenProvider;
        this.userService = userService;
        this.tokenBlacklistService = tokenBlacklistService;
    }

    /** 注册：用户名与邮箱唯一，密码以 BCrypt 哈希保存 */
    @Transactional
    public UserProfile register(RegisterRequest request) {
        Long usernameCount = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, request.getUsername()));
        if (usernameCount != null && usernameCount > 0) {
            throw BusinessException.badRequest("用户名已被占用");
        }

        Long emailCount = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getEmail, request.getEmail()));
        if (emailCount != null && emailCount > 0) {
            throw BusinessException.badRequest("该邮箱已被注册");
        }

        SysUser user = new SysUser();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setNickname(request.getUsername());
        user.setStatus(STATUS_ENABLED);
        userMapper.insert(user);

        return userService.toView(user);
    }

    /** 登录：账号可为用户名或邮箱 */
    public LoginResponse login(LoginRequest request) {
        SysUser user = userMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, request.getAccount())
                .or()
                .eq(SysUser::getEmail, request.getAccount()));

        // 账号不存在与密码错误返回同一提示，避免暴露账号是否注册
        if (user == null || !passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw BusinessException.unauthorized("账号或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_ENABLED) {
            throw BusinessException.forbidden("账号已被禁用");
        }

        LoginResponse response = new LoginResponse();
        response.setToken(tokenProvider.generateToken(user.getId(), user.getUsername()));
        response.setExpiresIn(tokenProvider.getExpirationSeconds());
        response.setUser(userService.toView(user));
        return response;
    }

    /**
     * 退出登录：把当前用户 JWT 的 jti 写入 Redis 黑名单（TTL = 剩余有效期）。
     *
     * <p>令牌无效/缺失时不报错——登出本应是幂等的；网关会拒绝已拉黑的令牌。
     */
    public void logout(String authorization) {
        if (authorization == null || !authorization.startsWith(BEARER_PREFIX)) {
            return;
        }
        try {
            Claims claims = tokenProvider.parse(authorization.substring(BEARER_PREFIX.length()).trim());
            String jti = claims.getId();
            if (jti == null) {
                return;
            }
            long ttlSeconds = claims.getExpiration() == null ? 0
                    : (claims.getExpiration().getTime() - System.currentTimeMillis()) / 1000L;
            tokenBlacklistService.blacklist(jti, ttlSeconds);
        }
        catch (JwtException | IllegalArgumentException ex) {
            log.debug("登出令牌无效，忽略：{}", ex.getMessage());
        }
    }

}
