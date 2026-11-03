package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 仪表盘「最近任务」时间线项（《API接口设计》9.1，M13 新增）。
 *
 * <p>四类任务统一投影：{@code type} 取 {@code RESUME_ANALYSIS} / {@code JOB_ANALYSIS} /
 * {@code OPTIMIZATION} / {@code INTERVIEW}；{@code refId} 为跳转所用主键
 * （分析类为简历 / 岗位 id，优化为优化记录 id，面试为会话 id）；
 * {@code title} 为该任务的资源名称（优化为「{简历名} · {岗位名}」）。
 *
 * <p>时间取自各表的 {@code created_at}（本表无更新语义的差异处理：任务线只展示"发生"，
 * 不展示"最后更新"）。
 */
public class DashboardRecentTask {

    /** RESUME_ANALYSIS / JOB_ANALYSIS / OPTIMIZATION / INTERVIEW */
    private String type;

    /** 资源名称（优化为「{简历名} · {岗位名}」） */
    private String title;

    /** 跳转主键：分析类为简历 / 岗位 id，优化为优化记录 id，面试为会话 id */
    private Long refId;

    private LocalDateTime happenedAt;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public LocalDateTime getHappenedAt() {
        return happenedAt;
    }

    public void setHappenedAt(LocalDateTime happenedAt) {
        this.happenedAt = happenedAt;
    }

}
