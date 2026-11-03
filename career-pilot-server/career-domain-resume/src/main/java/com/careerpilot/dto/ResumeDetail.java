package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 简历详情（《API接口设计》4.3）。
 *
 * **不返回**提取文本 content 与服务端路径 filePath——第一版不提供简历原文的
 * 预览与下载（G-9），简历文本仅在服务端用于 AI 分析。
 */
public class ResumeDetail {

    private Long id;

    private String name;

    /** 上传时的原始文件名 */
    private String fileName;

    /** PDF / DOCX / DOC / HTML / TXT */
    private String fileType;

    private LocalDateTime createdAt;

    private boolean analyzed;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isAnalyzed() {
        return analyzed;
    }

    public void setAnalyzed(boolean analyzed) {
        this.analyzed = analyzed;
    }

}
