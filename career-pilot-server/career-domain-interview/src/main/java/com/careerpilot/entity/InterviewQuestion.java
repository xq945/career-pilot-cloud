package com.careerpilot.entity;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;

/**
 * 面试题与回答，对应《数据库设计》中的 interview_question。
 *
 * <p><b>「同时为空、同时写入」不变量</b>（《API接口设计》§7.2/§7.3）：{@code answer}
 * 与评价字段（score/analysis/referenceAnswer/suggestions）在同一事务内一起写入——
 * 评价失败时不写入任何数据，因此**不存在"有回答、无得分"的中间状态**，
 * {@code answeredCount}（{@code answer IS NOT NULL}）即评价成功的提交数。
 *
 * <p><b>更新本表必须用实体方式</b>（{@code mapper.update(entity, wrapper)}），
 * 不能用 {@code lambdaUpdate().set(...)}：wrapper 路径不携带 {@code @TableField}
 * 上声明的 TypeHandler，{@code suggestions}（JSON 列）会被写坏。
 *
 * <p>JSON 列处理方式同 JobAnalysis / MatchRecord：autoResultMap + Jackson3TypeHandler。
 */
@TableName(value = "interview_question", autoResultMap = true)
public class InterviewQuestion {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String question;

    /** 题目类别（六枚举之一）；模型未给出或越界时为 null（可空，见 §7.3） */
    private String questionCategory;

    /** M15：出题时生成的知识点摘要（跨会话历史去重与展示；历史数据为 null 时回退题干前 30 字） */
    private String topicSummary;

    /** 用户回答，未作答为 null */
    private String answer;

    /** 参考答案：**评价时**产生（出题不生成），未作答为 null */
    private String referenceAnswer;

    /** 0–100，评价时写入；未作答为 null */
    private BigDecimal score;

    /** AI 点评，未作答为 null */
    private String analysis;

    @TableField(typeHandler = Jackson3TypeHandler.class)
    private List<String> suggestions;

    /** 题目顺序，同一会话内唯一（uk_interview_question_order），从 1 递增 */
    private Integer sortOrder;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionId() {
        return sessionId;
    }

    public void setSessionId(Long sessionId) {
        this.sessionId = sessionId;
    }

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

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getReferenceAnswer() {
        return referenceAnswer;
    }

    public void setReferenceAnswer(String referenceAnswer) {
        this.referenceAnswer = referenceAnswer;
    }

    public BigDecimal getScore() {
        return score;
    }

    public void setScore(BigDecimal score) {
        this.score = score;
    }

    public String getAnalysis() {
        return analysis;
    }

    public void setAnalysis(String analysis) {
        this.analysis = analysis;
    }

    public List<String> getSuggestions() {
        return suggestions;
    }

    public void setSuggestions(List<String> suggestions) {
        this.suggestions = suggestions;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

}
