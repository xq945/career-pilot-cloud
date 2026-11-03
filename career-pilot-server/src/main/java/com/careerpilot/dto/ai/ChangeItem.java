package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 逐项修改说明（《API接口设计》§6A.1 的 {@code changes} 元素、《数据库设计》5.11）。
 *
 * <p>同时是**模型输出元素、resume_optimization.changes 的 JSON 元素与 API 响应元素**——
 * 三处一套类型、零转换（与 MatchItem 的处理方式一致）。
 *
 * <p>继承 {@link AiOutputElement}：元素内出现约定外字段时收集并记日志后丢弃。
 *
 * <p>{@code before} / {@code after} 是"真实性"的主要举证方式：前者必须逐字来自简历原文，
 * 后者必须能在优化稿中找到。代码层并非只靠提示词：清洗后的产物会由
 * {@code ResumeOptimizationFactChecker} 在规范化后做包含性校验，并检查证据强度、
 * 数字上下文与正文覆盖账本。中文改写场景的误杀由“规范化 + 证据强度 + 风险分级”控制，
 * 而不是放弃校验（见《简历定向优化重构方案》§6.3 与第三阶段修复 A/C）。
 */
public class ChangeItem extends AiOutputElement {

    /** 所在章节（如「个人总结」「项目经历」） */
    private String section;

    /** 改动类型：REWRITE 改写 / KEYWORD 补关键词 / REORDER 调整顺序 / TRIM 精简；越界值被清洗为 null */
    private String type;

    /** 为什么这样改（结合岗位要求说明） */
    private String reason;

    /** 修改前的原文片段（逐字来自简历事实） */
    private String before;

    /** 修改后的片段（可在优化稿中找到） */
    private String after;

    public String getSection() {
        return section;
    }

    public void setSection(String section) {
        this.section = section;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getBefore() {
        return before;
    }

    public void setBefore(String before) {
        this.before = before;
    }

    public String getAfter() {
        return after;
    }

    public void setAfter(String after) {
        this.after = after;
    }

}
