package com.careerpilot.security;

/**
 * 认证上下文中的当前用户。
 *
 * 仅包含身份标识，不含密码等敏感字段。
 */
public record AuthenticatedUser(Long id, String username) {
}
