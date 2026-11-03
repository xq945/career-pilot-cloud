package com.careerpilot.dto;

/**
 * 用户资料，用于登录响应与当前用户接口（《API接口设计》3.3）。
 *
 * 作为对外视图，**不包含密码**；`avatar` 为可直接用于 `<img src>` 的访问地址。
 *
 * 本类**不提供构建方法**：装配统一在 {@code UserService.toView} 完成——
 * 数据库里存的是存储标识（如 `3/a1b2c3d4.jpg`），外部视图必须经存储层转换为
 * 访问地址，绕过转换直接暴露原始值会造成头像显示失败，故从源头禁止。
 */
public class UserProfile {

    private Long id;

    private String username;

    private String email;

    private String nickname;

    private String avatar;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    public String getAvatar() {
        return avatar;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
    }

}
