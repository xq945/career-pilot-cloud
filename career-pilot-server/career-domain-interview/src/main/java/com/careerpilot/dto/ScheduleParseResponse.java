package com.careerpilot.dto;

import java.math.BigDecimal;

/**
 * 解析预览的响应（《API接口设计》§9A.1，M17）。
 *
 * <p>**解析失败是正常业务态**（`success = false` + 可读 message，HTTP 200）——
 * 前端据此引导手工填写，而不是弹全局错误。
 */
public class ScheduleParseResponse {

    private boolean success;

    /** RULE / AI（成功时）；失败时为 null */
    private String parseSource;

    /** 规则 0.95 / AI 0.80；失败时为 null */
    private BigDecimal confidence;

    /** 失败原因（可读）；成功时为 null */
    private String message;

    /** 解析出的字段（成功时；字段内部仍可能部分为 null） */
    private ScheduleParseResult data;

    public static ScheduleParseResponse success(String source, BigDecimal confidence, ScheduleParseResult data) {
        ScheduleParseResponse response = new ScheduleParseResponse();
        response.success = true;
        response.parseSource = source;
        response.confidence = confidence;
        response.data = data;
        return response;
    }

    public static ScheduleParseResponse failure(String message) {
        ScheduleParseResponse response = new ScheduleParseResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
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

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public ScheduleParseResult getData() {
        return data;
    }

    public void setData(ScheduleParseResult data) {
        this.data = data;
    }

}
