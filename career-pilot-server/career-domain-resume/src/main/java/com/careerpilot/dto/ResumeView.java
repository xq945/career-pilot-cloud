package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 简历列表项（《API接口设计》4.2）。
 *
 * `analyzed` 表示该简历是否已有至少一条成功分析记录
 * （`resume_analysis` 中存在 `status = 'SUCCESS'` 的行），前端据它显示「已分析 / 未分析」标签。
 */
public class ResumeView {

    private Long id;

    private String name;

    /** 上传时的原始文件名（含扩展名）：列表页的「下载」入口用它作为下载后的文件名 */
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
