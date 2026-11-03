package com.careerpilot.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 创建 / 更新日程的请求体（《API接口设计》§9A.2 / §9A.5，M17；更新为全量更新，共用本结构）。
 *
 * <p>`parseSource` / `confidence` 由前端在"确认解析结果"时回传（缺省 `MANUAL` / null）——
 * 解析来源随记录落库，用于追溯"这条日程是规则解析、AI 兜底还是手工填的"。
 *
 * <p>时间必须为**未来**（过去时间 → 400），本 DTO 只做非空校验，时间范围在服务层判定
 * （@Future 与"今天稍晚"的语义在时区处理上不直观，统一在服务层用同一时钟判断）。
 */
public class InterviewScheduleSaveRequest {

    @NotBlank(message = "公司名不能为空")
    @Size(max = 100, message = "公司名不能超过 100 个字符")
    private String companyName;

    @NotBlank(message = "岗位名不能为空")
    @Size(max = 100, message = "岗位名不能超过 100 个字符")
    private String position;

    @NotNull(message = "面试时间不能为空")
    private LocalDateTime interviewTime;

    @Pattern(regexp = "ONSITE|VIDEO|PHONE", message = "面试形式必须是 ONSITE、VIDEO、PHONE 之一")
    private String interviewType;

    @Size(max = 500, message = "会议链接不能超过 500 个字符")
    private String meetingLink;

    @Min(value = 1, message = "轮次至少为 1")
    @Max(value = 10, message = "轮次最多为 10")
    private Integer roundNumber;

    @Size(max = 50, message = "面试官不能超过 50 个字符")
    private String interviewer;

    @Size(max = 1000, message = "备注不能超过 1000 个字符")
    private String notes;

    /** RULE/AI/MANUAL（缺省 MANUAL；越界由服务层归一） */
    @Size(max = 10, message = "解析来源标识过长")
    private String parseSource;

    @DecimalMin(value = "0.0", message = "置信度不能为负")
    @DecimalMax(value = "1.0", message = "置信度不能大于 1")
    private BigDecimal confidence;

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

    public String getParseSource() {
        return parseSource;
    }

    public void setParseSource(String parseSource) {
        this.parseSource = parseSource;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public void setConfidence(BigDecimal confidence) {
        this.confidence = confidence;
    }

}
