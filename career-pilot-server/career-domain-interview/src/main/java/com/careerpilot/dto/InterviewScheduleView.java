package com.careerpilot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 面试日程视图（列表项与详情共用；《API接口设计》§9A.3 / §9A.4，M17）。
 *
 * `parseSource`（RULE/AI/MANUAL）与 `parseConfidence`（0.95/0.80/null）供前端展示
 * 解析来源徽标——AI 兜底结果需要提示"请重点核对"。
 */
public class InterviewScheduleView {

    private Long id;

    private String companyName;

    private String position;

    private LocalDateTime interviewTime;

    /** ONSITE/VIDEO/PHONE */
    private String interviewType;

    private String meetingLink;

    private Integer roundNumber;

    private String interviewer;

    private String notes;

    /** PENDING/COMPLETED/CANCELLED/EXPIRED */
    private String status;

    private String parseSource;

    private BigDecimal parseConfidence;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
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
