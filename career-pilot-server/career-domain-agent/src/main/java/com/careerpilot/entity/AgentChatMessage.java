package com.careerpilot.entity;

import java.time.LocalDateTime;
import java.util.Map;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

import com.careerpilot.dto.AgentToolResultPayload;

/**
 * 智能体对话与工具调用记录，对应《数据库设计》§2 表 10（agent_chat_message）。
 *
 * <p>三种角色：{@link #ROLE_USER} / {@link #ROLE_ASSISTANT} / {@link #ROLE_TOOL}；
 * 整轮消息同一事务写入（§8.1/A26），truncated=true 属成功路径、照常落库（R05）。
 *
 * <p><b>status 的载体说明</b>：表结构没有 status 列，§8.3 响应的 {@code status} 取自
 * {@code tool_result} 载荷（{@link AgentToolResultPayload#getStatus()}）——这是刻意的设计，
 * 不是漏字段。工具原始入参（tool_arguments）与结果载荷（tool_result）**只落库、不对外返回**：
 * §8.3 对工具消息只暴露工具名与状态（原始内容可能含大段简历文本）。
 *
 * <p>JSON 列的处理方式同 JobAnalysis / MatchRecord（《数据库设计》5.5）：
 * autoResultMap + Jackson3TypeHandler（更新时须用实体方式，wrapper 的 .set() 不携带 TypeHandler）。
 */
@TableName(value = "agent_chat_message", autoResultMap = true)
public class AgentChatMessage {

    public static final String ROLE_USER = "user";

    public static final String ROLE_ASSISTANT = "assistant";

    public static final String ROLE_TOOL = "tool";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 关联 agent_session.session_id */
    private String sessionId;

    /** user / assistant / tool（见常量） */
    private String role;

    /** user/assistant 的消息正文；tool 行为 null */
    private String content;

    /** role=tool 时的工具名 */
    private String toolName;

    /** role=tool 时的调用入参（JSON 对象）；只落库、不对外返回 */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private Map<String, Object> toolArguments;

    /** role=tool 时的结果载荷（status/elapsedMillis/summary）；只落库，§8.3 只暴露 status */
    @TableField(typeHandler = Jackson3TypeHandler.class)
    private AgentToolResultPayload toolResult;

    private LocalDateTime createdAt;

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

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getToolName() {
        return toolName;
    }

    public void setToolName(String toolName) {
        this.toolName = toolName;
    }

    public Map<String, Object> getToolArguments() {
        return toolArguments;
    }

    public void setToolArguments(Map<String, Object> toolArguments) {
        this.toolArguments = toolArguments;
    }

    public AgentToolResultPayload getToolResult() {
        return toolResult;
    }

    public void setToolResult(AgentToolResultPayload toolResult) {
        this.toolResult = toolResult;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

}
