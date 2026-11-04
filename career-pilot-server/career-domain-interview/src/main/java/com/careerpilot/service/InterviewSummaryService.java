package com.careerpilot.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.ai.AiCallResult;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.entity.InterviewQuestion;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.entity.Job;
import com.careerpilot.mapper.InterviewQuestionMapper;
import com.careerpilot.mapper.InterviewSessionMapper;
import com.careerpilot.service.ai.InterviewAiService;
import com.careerpilot.service.ai.InterviewSummaryFallback;

/**
 * 总评生成（阶段 8）：同步完成路径与 RabbitMQ 消费者**共用同一套生成/降级逻辑**。
 *
 * <p>纪律与 M15 一致：模型失败 → 写确定性降级总评（{@code DEGRADED}），永不因总评失败
 * 让答题失败；DB 异常向上抛出（异步侧由重试/DLQ 承接，同步侧由提交路径记日志）。
 *
 * <p>完成路径使用**创建时钉住的模型服务**（会话快照列 {@code llm_provider_key}），
 * 与重新生成接口一致（方案 §4.4 · A7）。
 */
@Service
public class InterviewSummaryService {

    private static final Logger log = LoggerFactory.getLogger(InterviewSummaryService.class);

    private final InterviewSessionMapper sessionMapper;

    private final InterviewQuestionMapper questionMapper;

    private final JobService jobService;

    private final InterviewAiService interviewAiService;

    private final InterviewTxService interviewTxService;

    private final InterviewSummaryFallback summaryFallback;

    public InterviewSummaryService(InterviewSessionMapper sessionMapper,
                                   InterviewQuestionMapper questionMapper,
                                   JobService jobService,
                                   InterviewAiService interviewAiService,
                                   InterviewTxService interviewTxService,
                                   InterviewSummaryFallback summaryFallback) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
        this.jobService = jobService;
        this.interviewAiService = interviewAiService;
        this.interviewTxService = interviewTxService;
        this.summaryFallback = summaryFallback;
    }

    /**
     * 同步路径：会话刚完成（调用方已持有会话、岗位与全部题目）。
     *
     * @param totalScore 刚写入的题均分（传入而非从 session 读，避免调用方实体未刷新）
     * @return 实际写入的来源（AI / DEGRADED）；降级写入也失败时返回 null
     */
    public String generateSafely(InterviewSession session, Job job,
                                 List<InterviewQuestion> questions, BigDecimal totalScore) {
        try {
            AiCallResult<InterviewSummaryAiOutput> summary = interviewAiService.summarize(
                    job, session.getQuestionType(), questions, totalScore, session.getLlmProviderKey());
            interviewTxService.saveSummary(session.getId(), summary.value(),
                    InterviewTxService.SUMMARY_SOURCE_AI);
            return InterviewTxService.SUMMARY_SOURCE_AI;
        }
        catch (RuntimeException ex) {
            log.warn("总评生成失败，写入降级版（系统汇总）：sessionId={} 异常={}",
                    session.getId(), ex.getClass().getSimpleName());
            try {
                InterviewSummaryAiOutput fallback = summaryFallback.build(questions, totalScore);
                interviewTxService.saveSummary(session.getId(), fallback,
                        InterviewTxService.SUMMARY_SOURCE_DEGRADED);
                return InterviewTxService.SUMMARY_SOURCE_DEGRADED;
            }
            catch (RuntimeException fallbackEx) {
                log.warn("降级总评写入失败，总评保持缺失：sessionId={} 异常={}",
                        session.getId(), fallbackEx.getClass().getSimpleName());
                return null;
            }
        }
    }

    /**
     * 异步消费者入口：按会话 ID 重新加载并生成。
     *
     * <p>幂等：已是模型总评（{@code AI}）的会话直接跳过——消息重投不会重复调用模型。
     * 归属不符按 404 处理（不泄露他人会话是否存在）。
     *
     * @return 实际写入的来源；已生成时返回既有来源
     */
    public String generateForSession(Long userId, Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null || !Objects.equals(session.getUserId(), userId)) {
            throw BusinessException.notFound("面试会话不存在");
        }
        if (InterviewTxService.SUMMARY_SOURCE_AI.equals(session.getSummarySource())) {
            log.info("总评已存在（AI），跳过重复消费：sessionId={}", sessionId);
            return InterviewTxService.SUMMARY_SOURCE_AI;
        }
        List<InterviewQuestion> questions = questionMapper.selectList(
                Wrappers.<InterviewQuestion>lambdaQuery()
                        .eq(InterviewQuestion::getSessionId, sessionId)
                        .orderByAsc(InterviewQuestion::getSortOrder));
        Job job = jobService.getJobById(session.getJobId());
        if (job == null) {
            // 跨库外键已删除，岗位被误删时这里会走到：无岗位描述无法生成总评，交给重试/DLQ
            throw new IllegalStateException("关联岗位不存在：sessionId=" + sessionId
                    + " jobId=" + session.getJobId());
        }
        String source = generateSafely(session, job, questions, session.getTotalScore());
        log.info("异步总评已写入：sessionId={} source={}", sessionId, source);
        return source;
    }
}
