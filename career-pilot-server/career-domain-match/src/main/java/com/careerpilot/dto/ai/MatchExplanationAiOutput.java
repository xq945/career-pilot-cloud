package com.careerpilot.dto.ai;

import java.util.List;

import com.careerpilot.ai.AiOutputElement;

/**
 * 匹配解释的模型输出结构（《Agent设计》6.3、《API接口设计》6.1）。
 *
 * <p>**刻意不含任何分数字段**：总分与维度分由后端确定性计算（见 MatchScoringService），
 * 模型即使返回 {@code matchScore} 之类的越界字段，也会被 {@link AiOutputElement} 收集后
 * 由清洗器记日志并丢弃——这是「模型不能覆盖总分」的落点之一（单元测试对该行为有断言）。
 */
public class MatchExplanationAiOutput extends AiOutputElement {

    /** 已匹配项（《Agent设计》6.3：明细的元素为对象 {text, dimension}） */
    private List<MatchItem> matchedItems;

    /** 部分匹配项 */
    private List<MatchItem> partialItems;

    /** 差距项（文案必须含「简历未体现」等措辞） */
    private List<MatchItem> missingItems;

    /** 一句话总评（仅解释，不参与计分） */
    private String summary;

    /** 改进建议（按优先级排列） */
    private List<String> suggestions;

    public List<MatchItem> getMatchedItems() {
        return matchedItems;
    }

    public void setMatchedItems(List<MatchItem> matchedItems) {
        this.matchedItems = matchedItems;
    }

    public List<MatchItem> getPartialItems() {
        return partialItems;
    }

    public void setPartialItems(List<MatchItem> partialItems) {
        this.partialItems = partialItems;
    }

    public List<MatchItem> getMissingItems() {
        return missingItems;
    }

    public void setMissingItems(List<MatchItem> missingItems) {
        this.missingItems = missingItems;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

}
