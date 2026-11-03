package com.careerpilot.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 智能体会话，对应《数据库设计》§2 表 9（agent_session）。
 *
 * <p>时间戳维护（《数据库设计》5.9 / G-8）：{@code updated_at} 是会话列表的排序依据，
 * 而 {@code ON UPDATE CURRENT_TIMESTAMP} 只在**本行**被 UPDATE 时触发、插入消息不会触发——
 * 因此每写入一条 {@code agent_chat_message}，应用层须在**同一事务内显式更新**本行的
 * updated_at（见 {@code AgentTxService.saveTurn}）。
 *
 * <p>会话元数据仅在**首轮整轮成功**时随该事务创建（《API接口设计》§8.1/A26）：
 * 模型调用失败（502）不创建会话、不写入任何消息。
 */
@TableName("agent_session")
public class AgentSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 应用层生成的会话标识（UUID 去连字符，32 字符；对前端暴露的是它而非自增 id） */
    private String sessionId;

    /** 会话标题：取首条用户消息的前若干字符，VARCHAR(100) */
    private String title;

    private LocalDateTime createdAt;

    /** 会话列表排序依据；每条消息写入时由应用层同事务显式更新（G-8） */
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

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
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
