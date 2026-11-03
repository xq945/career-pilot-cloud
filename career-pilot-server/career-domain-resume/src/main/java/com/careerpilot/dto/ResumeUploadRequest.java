package com.careerpilot.dto;

import jakarta.validation.constraints.Size;

/**
 * 上传简历的文本字段（《API接口设计》4.1；文件本身由 Controller 以 {@code @RequestPart} 单独接收）。
 *
 * `name` 为可选：不传或空白时取原文件名（超 100 字符截断）；
 * **显式传入超过 100 字符返回 40001**（文件名的截断规则不适用于用户输入）。
 */
public class ResumeUploadRequest {

    @Size(max = 100, message = "简历名称不能超过 100 个字符")
    private String name;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

}
