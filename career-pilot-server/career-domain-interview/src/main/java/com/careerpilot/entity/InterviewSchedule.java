package com.careerpilot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 面试日程，对应《数据库设计》中的 interview_schedule（V1.11 新增，M17）。
 *
 * <p>管理**真实面试安排**（与"M模拟面试"是不同语义）：支持粘贴邀请文本解析录入
 * （{@code parseSource} / {@code parseConfidence} 追溯来源与置信度，信任排序：规则 &gt; AI &gt; 手工）。
 *
 * <p>状态机（《数据库设计》5.16）：{@code PENDING → COMPLETED / CANCELLED}（用户操作）；
 * {@code PENDING → EXPIRED}（**仅系统**定时任务写入）。三个终态不可回退；仅 {@code PENDING} 可编辑。
 *
 * <p>删除为**物理删除**（日程无下游引用，与其余业务表的逻辑删除刻意不同，见 5.16）。
 */
@TableName("interview_schedule")
public class InterviewSchedule {

    /** 状态：待面试（唯一可编辑、可流转的活跃态） */
    public static final String STATUS_PENDING = "PENDING";

    /** 状态：已完成（终态） */
    public static final String STATUS_COMPLETED = "COMPLETED";

    /** 状态：已取消（终态） */
    public static final String STATUS_CANCELLED = "CANCELLED";

    /** 状态：已过期（终态，仅系统写入） */
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 解析来源：规则 */
    public static final String SOURCE_RULE = "RULE";

    /** 解析来源：AI 兜底 */
    public static final String SOURCE_AI = "AI";

    /** 解析来源：手工填写 */
    public static final String SOURCE_MANUAL = "MANUAL";

    /** 面试形式：现场 */
    public static final String TYPE_ONSITE = "ONSITE";

    /** 面试形式：视频（默认） */
    public static final String TYPE_VIDEO = "VIDEO";

    /** 面试形式：电话 */
    public static final String TYPE_PHONE = "PHONE";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String companyName;

    private String position;

    private LocalDateTime interviewTime;

    /** ONSITE/VIDEO/PHONE（见常量） */
    private String interviewType;

    private String meetingLink;

    private Integer roundNumber;

    private String interviewer;

    private String notes;

    /** PENDING/COMPLETED/CANCELLED/EXPIRED（见常量） */
    private String status;

    /** RULE/AI/MANUAL（见常量） */
    private String parseSource;

    /** 解析置信度：规则 0.95 / AI 0.80 / 手工 null */
    private BigDecimal parseConfidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    /** 是否处于可编辑/可流转的活跃态 */
    public boolean pending() {
        return STATUS_PENDING.equals(status);
    }

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

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getPosition() {
        return position;
    }

    public void setPosition(String position) {
        this.position = position;
    }

    public LocalDateTime getInterviewTime() {
        return interviewTime;
    }

    public void setInterviewTime(LocalDateTime interviewTime) {
        this.interviewTime = interviewTime;
    }

    public String getInterviewType() {
        return interviewType;
    }

    public void setInterviewType(String interviewType) {
        this.interviewType = interviewType;
    }

    public String getMeetingLink() {
        return meetingLink;
    }

    public void setMeetingLink(String meetingLink) {
        this.meetingLink = meetingLink;
    }

    public Integer getRoundNumber() {
        return roundNumber;
    }

    public void setRoundNumber(Integer roundNumber) {
        this.roundNumber = roundNumber;
    }

    public String getInterviewer() {
        return interviewer;
    }

    public void setInterviewer(String interviewer) {
        this.interviewer = interviewer;
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getParseSource() {
        return parseSource;
    }

    public void setParseSource(String parseSource) {
        this.parseSource = parseSource;
    }

    public BigDecimal getParseConfidence() {
        return parseConfidence;
    }

    public void setParseConfidence(BigDecimal parseConfidence) {
        this.parseConfidence = parseConfidence;
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
