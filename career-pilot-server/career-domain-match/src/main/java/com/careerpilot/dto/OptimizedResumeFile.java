package com.careerpilot.dto;

/**
 * 导出优化稿的载体（《API接口设计》§6A.6）。
 *
 * <p>{@code content} 是当前生效的优化稿（用户编辑稿优先，无编辑稿时回落模型原稿）；
 * Controller 据此生成 DOCX 字节流并组装响应头，本结构**不落盘**。
 */
public class OptimizedResumeFile {

    private final String fileName;

    private final String content;

    public OptimizedResumeFile(String fileName, String content) {
        this.fileName = fileName;
        this.content = content;
    }

    public String getFileName() {
        return fileName;
    }

    public String getContent() {
        return content;
    }

}
