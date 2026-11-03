package com.careerpilot.dto;

/**
 * 简历文件内容的承载（《API接口设计》4.7）：Service 从存储层读出后交给 Controller 组装响应。
 *
 * 注意：这**不是** API 响应结构——该接口的响应体是文件二进制（全项目唯一的非 JSON 成功响应），
 * 响应头（Content-Type / Content-Disposition）由 Controller 按 {@code fileType} 分派。
 */
public record ResumeFile(byte[] content, String fileName, String fileType) {
}
