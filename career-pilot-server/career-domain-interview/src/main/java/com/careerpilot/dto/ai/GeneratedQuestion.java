package com.careerpilot.dto.ai;

import com.careerpilot.ai.AiOutputElement;

/**
 * 模型生成的单道面试题（《Agent设计》§4.5 输出结构的子集）。
 *
 * 含题干、类别与知识点摘要（M15）三个字段：**不含**"考察点 / 参考答案"——参考答案在
 * **评价时**产生（《API接口设计》§7.2/§7.3：`referenceAnswer` 与评价字段同时写入、
 * 未作答时同时为 null），出题阶段落库的只有题干、类别与摘要。
 *
 * 继承 {@link AiOutputElement}：模型多给的字段（如"考察点"）被收集后由清洗器记日志丢弃。
 */
public class GeneratedQuestion extends AiOutputElement {

    private String question;

    /** 六枚举之一（JAVA/SPRING/MYSQL/REDIS/PROJECT/MIXED）；越界时清洗为 null */
    private String questionCategory;

    /** M15：知识点摘要（≤10 字，如「Redis 持久化机制对比」）；供跨会话历史去重与展示，清洗后可为 null */
    private String topicSummary;

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public String getQuestionCategory() {
        return questionCategory;
    }

    public void setQuestionCategory(String questionCategory) {
        this.questionCategory = questionCategory;
    }

    public String getTopicSummary() {
        return topicSummary;
    }

    public void setTopicSummary(String topicSummary) {
        this.topicSummary = topicSummary;
    }

}
