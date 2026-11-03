package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 日程状态流转的请求体（《API接口设计》§9A.6，M17）。
 *
 * <p>只接受 `COMPLETED` / `CANCELLED`——`EXPIRED` 是系统状态，不接受用户写入（白名单即拒绝）。
 */
public class InterviewScheduleStatusRequest {

    @NotBlank(message = "目标状态不能为空")
    @Pattern(regexp = "COMPLETED|CANCELLED", message = "状态只能是 COMPLETED 或 CANCELLED")
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}
