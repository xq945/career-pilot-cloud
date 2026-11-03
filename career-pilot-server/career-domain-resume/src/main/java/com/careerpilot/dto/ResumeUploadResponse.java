package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 上传简历的成功响应（《API接口设计》4.1）。
 *
 * **不返回**服务端存储路径，也不返回提取文本；`textExtracted` 为 true 表示
 * 文本已成功提取入库（提取失败会整体失败返回 400，不存在"已上传未提取"的中间状态）。
 */
public class ResumeUploadResponse {

    private Long id;

    private String name;

    private String fileName;

    /** PDF / DOCX / DOC / HTML / TXT */
    private String fileType;

    private boolean textExtracted;

    private LocalDateTime createdAt;

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

    public boolean isTextExtracted() {
        return textExtracted;
    }

    public void setTextExtracted(boolean textExtracted) {
        this.textExtracted = textExtracted;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
