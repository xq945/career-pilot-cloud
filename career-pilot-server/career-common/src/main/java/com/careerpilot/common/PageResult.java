package com.careerpilot.common;

import java.util.List;

import com.baomidou.mybatisplus.core.metadata.IPage;

/**
 * 统一分页响应结构（《API接口设计》§10.1）：{@code { records, page, size, total }}。
 *
 * 无数据时 records 为空数组、total 为 0。泛型供后续岗位、匹配、面试等列表接口复用。
 */
public class PageResult<T> {

    private List<T> records;

    private long page;

    private long size;

    private long total;

    public PageResult() {
    }

    public PageResult(List<T> records, long page, long size, long total) {
        this.records = records;
        this.page = page;
        this.size = size;
        this.total = total;
    }

    /**
     * 由 MyBatis-Plus 的分页结果装配：分页元数据取自 {@code source}，
     * 展示记录取自 {@code records}（查询实体与响应视图通常不是同一类型）。
     */
    public static <S, T> PageResult<T> of(IPage<S> source, List<T> records) {
        return new PageResult<>(records, source.getCurrent(), source.getSize(), source.getTotal());
    }

    public List<T> getRecords() {
        return records;
    }

    public void setRecords(List<T> records) {
        this.records = records;
    }

    public long getPage() {
        return page;
    }

    public void setPage(long page) {
        this.page = page;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

}
