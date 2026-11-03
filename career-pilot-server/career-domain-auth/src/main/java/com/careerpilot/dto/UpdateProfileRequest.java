package com.careerpilot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 更新资料请求（《API接口设计》3.4）。
 *
 * 仅包含昵称一个字段：头像由 3.6 / 3.7 两个专门接口维护，**不在本请求契约中**
 * （请求中出现 avatar 字段会被 Jackson 默认忽略、不报错）。
 *
 * 昵称设为**必填**：字段缺失与传 null 在 Java DTO 中不可区分，若允许缺失，
 * `PUT {}` 会产生"意外清空昵称"的歧义（V2.2 契约调整）；
 * 清空昵称的唯一表达方式是**空串**（或全空白）。
 */
public class UpdateProfileRequest {

    @NotNull(message = "昵称不能为空")
    @Size(max = 50, message = "昵称不能超过 50 个字符")
    private String nickname;

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

}
