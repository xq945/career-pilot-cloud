package com.careerpilot.dto;

/**
 * 登录成功响应（《API接口设计》2 节）。
 */
public class LoginResponse {

    private String token;

    private String tokenType = "Bearer";

    /** 有效期，单位秒 */
    private long expiresIn;

    private UserProfile user;

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public UserProfile getUser() {
        return user;
    }

    public void setUser(UserProfile user) {
        this.user = user;
    }

}
