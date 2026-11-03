package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * 岗位求职进度切换的请求体（《API接口设计》§5.8，M21）。
 *
 * <p>白名单即拒绝：八个枚举值之外的输入在 DTO 层返回 40001；服务层再兜底一次
 * （防绕过 Bean Validation 的调用路径）。不设状态机——求职进度可任意切换/回退。
 */
public class JobStatusRequest {

    @NotBlank(message = "目标状态不能为空")
    @Pattern(regexp = "PENDING|APPLIED|WRITTEN|INTERVIEW_1|INTERVIEW_2|HR|OFFER|REJECTED",
            message = "状态只能是 PENDING/APPLIED/WRITTEN/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED")
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

}
