package com.careerpilot.dto;

import java.time.LocalDateTime;

/**
 * 解析出的日程字段（《API接口设计》§9A.1 的 data；**不落库**，用户确认后走创建接口）。
 *
 * 任一字段都可能为 null（未识别）——前端回填表单后由用户补齐。
 */
public class ScheduleParseResult {

    private String companyName;

    private String position;

    private LocalDateTime interviewTime;

    /** ONSITE/VIDEO/PHONE */
    private String interviewType;

    private String meetingLink;

    private Integer roundNumber;

    private String interviewer;

    private String notes;

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

}
