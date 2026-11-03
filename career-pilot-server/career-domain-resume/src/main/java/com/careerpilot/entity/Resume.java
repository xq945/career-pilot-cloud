package com.careerpilot.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 简历表实体，对应《数据库设计》中的 resume。
 *
 * 逻辑删除约定（《数据库设计》5.4，R07）：**手写 status 条件，不用 @TableLogic**——
 * 查询自行附加 {@code status = 1}、删除写 {@code status = 0}。
 *
 * 本实体含 content（提取文本）与 filePath（存储标识），二者**均不向前端返回**
 * （《API接口设计》4.3：不提供原文预览与下载）。
 */
@TableName("resume")
public class Resume {

    public static final int STATUS_ACTIVE = 1;

    public static final int STATUS_DELETED = 0;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** 用户上传时的原始文件名 */
    private String fileName;

    /** PDF / DOCX / DOC / HTML / TXT */
    private String fileType;

    /** 相对 storage-root 的存储标识，不向前端暴露 */
    private String filePath;

    /** 提取的简历文本（入库前按上限截断） */
    private String content;

    /** 1 有效，0 已删除 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
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

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
