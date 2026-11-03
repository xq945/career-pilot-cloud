package com.careerpilot.common;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * 分页请求参数（《API接口设计》§10.1）：页码从 1 开始，size 默认 10、最大 100。
 *
 * **越界一律 400 / 40001**（A19，不做静默截断）。约束写在 DTO 字段上、
 * 由 Controller 以 {@code @Valid} 触发的 Bean Validation 校验，
 * 错误经 {@code GlobalExceptionHandler#handleValidation} 返回 40001 + 字段级 data。
 *
 * 注意：不要改用 {@code @RequestParam + @Validated} 直接约束方法参数——那样抛的是
 * {@code ConstraintViolationException}，不在全局处理器的映射范围内，会落到 50000。
 */
public class PageQuery {

    @Min(value = 1, message = "页码从 1 开始")
    private Integer page = 1;

    @Min(value = 1, message = "每页数量至少为 1")
    @Max(value = 100, message = "每页数量不能超过 100")
    private Integer size = 10;

    public Integer getPage() {
        return page;
    }

    public void setPage(Integer page) {
        this.page = page;
    }

    public Integer getSize() {
        return size;
    }

    public void setSize(Integer size) {
        this.size = size;
    }

}
