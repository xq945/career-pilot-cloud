package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 解析面试邀请的请求体（《API接口设计》§9A.1，M17）。
 */
public class ScheduleParseRequest {

    @NotBlank(message = "邀请文本不能为空")
    @Size(max = 5000, message = "邀请文本不能超过 5000 个字符")
    private String rawText;

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

}
