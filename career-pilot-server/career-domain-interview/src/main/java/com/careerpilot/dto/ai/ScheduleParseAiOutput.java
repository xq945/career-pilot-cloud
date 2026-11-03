package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 模型对面试邀请的解析输出（M17，《API接口设计》§9A.1 的 AI 兜底）。
 *
 * <p>{@code interviewTime} 为 ISO-8601 本地时间字符串（`yyyy-MM-ddTHH:mm`）——
 * 保持字符串由调用方解析，解析失败即视为该字段缺失（不整段失败）。
 * 继承 {@link AiOutputElement}：模型多给的字段被收集后由清洗器记日志丢弃。
 */
public class ScheduleParseAiOutput extends AiOutputElement {

    private String companyName;

    private String position;

    /** ISO-8601 本地时间（yyyy-MM-ddTHH:mm）；未识别时为 null */
    private String interviewTime;

    /** ONSITE/VIDEO/PHONE；越界时清洗为 null（服务层回落 VIDEO） */
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

    public String getInterviewTime() {
        return interviewTime;
    }

    public void setInterviewTime(String interviewTime) {
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
