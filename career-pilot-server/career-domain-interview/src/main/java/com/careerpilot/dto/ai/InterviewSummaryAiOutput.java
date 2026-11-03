package com.careerpilot.dto.ai;

import java.util.List;

/**
 * 总评场景的模型输出结构（《API接口设计》§7.4 V2.5：报告页的总评区）。
 *
 * 与落库结构（interview_session 的 overall_summary + 三个 JSON 列）
 * 及报告响应（{@link com.careerpilot.dto.InterviewReport} 的对应字段）**逐字段一致**。
 *
 * 清洗规则：`summary` ≤ 2000 字符；三个列表各 ≤ 10 项（上限文档未规定，取标签语义的合理值）。
 */
public class InterviewSummaryAiOutput {

    /** 一句话总评（≤ 2000 字符） */
    private String summary;

    /** 强项标签（短句，≤ 10 项） */
    private List<String> strengths;

    /** 薄弱标签（短句，≤ 10 项） */
    private List<String> weaknesses;

    /** 提升建议（可执行，≤ 10 项） */
    private List<String> recommendations;

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getStrengths() {
        return strengths;
    }

    public void setStrengths(List<String> strengths) {
        this.strengths = strengths;
    }

    public List<String> getWeaknesses() {
        return weaknesses;
    }

    public void setWeaknesses(List<String> weaknesses) {
        this.weaknesses = weaknesses;
    }

    public List<String> getRecommendations() {
        return recommendations;
    }

    public void setRecommendations(List<String> recommendations) {
        this.recommendations = recommendations;
    }

}
