package com.careerpilot.dto.ai;

import java.util.List;

import com.careerpilot.ai.AiOutputElement;

/**
 * 简历定向优化的模型输出结构（《API接口设计》§6A.1、《Agent设计》6.4 的清洗范式）。
 *
 * <p>**三不原则的落点**：
 * <ul>
 *   <li>无任何分数字段——优化前后分数一律由 {@code MatchScoringService} 计算，
 *       模型返回 {@code matchScore} 之类的越界字段会被 {@link AiOutputElement} 收集后丢弃；</li>
 *   <li>无"补充事实"字段——简历缺失的信息只能写入 {@code gapQuestions}（问用户），
 *       不得由模型补全进 {@code optimizedContent}；</li>
 *   <li>不落失败结果——{@code optimizedContent} 清洗后为空视为输出无法解析（50201），
 *       不写入任何记录。</li>
 * </ul>
 */
public class ResumeOptimizationAiOutput extends AiOutputElement {

    /** 优化后的简历全文（Markdown；模型原稿，落库后不可变） */
    private String optimizedContent;

    /** 逐项修改说明（每条含 section / type / reason / before / after） */
    private List<ChangeItem> changes;

    /** 待用户确认的问题（岗位有要求而简历缺少事实的内容；不由模型自动补全） */
    private List<String> gapQuestions;

    public String getOptimizedContent() {
        return optimizedContent;
    }

    public void setOptimizedContent(String optimizedContent) {
        this.optimizedContent = optimizedContent;
    }

    public List<ChangeItem> getChanges() {
        return changes;
    }

    public void setChanges(List<ChangeItem> changes) {
        this.changes = changes;
    }

    public List<String> getGapQuestions() {
        return gapQuestions;
    }

    public void setGapQuestions(List<String> gapQuestions) {
        this.gapQuestions = gapQuestions;
    }

}
