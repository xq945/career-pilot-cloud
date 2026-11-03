package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 保存用户编辑稿的请求（《API接口设计》§6A.4）：{@code {content}}。
 *
 * <p>长度上限与简历文本一致（20000 字符，见 §10.5 字段对照表）；
 * 写入目标为 {@code edited_content}，**不覆盖**模型原稿 {@code optimized_content}。
 */
public class ResumeOptimizationContentRequest {

    @NotBlank(message = "content 不能为空")
    @Size(max = 20000, message = "content 不能超过 20000 字符")
    private String content;

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

}
