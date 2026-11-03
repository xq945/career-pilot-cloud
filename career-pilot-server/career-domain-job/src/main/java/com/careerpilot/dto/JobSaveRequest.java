package com.careerpilot.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建 / 更新岗位的请求体（《API接口设计》5.1、5.4）。
 *
 * 创建与更新**共用同一结构**：更新是全量更新语义——`jobName` 与 `description`
 * 必须提交且校验同创建，不存在"省略字段即保留原值"的部分更新行为；
 * `companyName` 省略或传 `null` 一律表示清空（不保留原值）。
 *
 * 长度上限对应库列（《API接口设计》10.5）：company_name/job_name VARCHAR(100)、
 * description LONGTEXT（业务上限 10000）。
 */
public class JobSaveRequest {

    @Size(max = 100, message = "公司名称不能超过 100 个字符")
    private String companyName;

    @NotBlank(message = "岗位名称不能为空")
    @Size(max = 100, message = "岗位名称不能超过 100 个字符")
    private String jobName;

    @NotBlank(message = "岗位描述不能为空")
    @Size(max = 10000, message = "岗位描述不能超过 10000 个字符")
    private String description;

    /**
     * 投递网页链接（M19 新增，选填）：前端列表「投递」按钮跳转的目标地址。
     *
     * **不加 `@Pattern`**：空串/全空白表示清空（与 {@code companyName} 同语义），
     * 而 `@Pattern` 对空串判失败、对 null 放行，无法同时表达"可清空"与"填了就必须合法"。
     * 协议白名单（http/https）在 {@code JobService.normalizeApplyUrl} 中校验（40000）。
     */
    @Size(max = 500, message = "投递链接不能超过 500 个字符")
    private String applyUrl;

    public String getCompanyName() {
        return companyName;
    }

    public void setCompanyName(String companyName) {
        this.companyName = companyName;
    }

    public String getJobName() {
        return jobName;
    }

    public void setJobName(String jobName) {
        this.jobName = jobName;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getApplyUrl() {
        return applyUrl;
    }

    public void setApplyUrl(String applyUrl) {
        this.applyUrl = applyUrl;
    }

}
