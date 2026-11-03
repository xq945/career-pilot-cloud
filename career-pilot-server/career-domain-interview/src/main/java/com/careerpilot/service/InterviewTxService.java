package com.careerpilot.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.common.BusinessException;
import com.careerpilot.dto.ai.GeneratedQuestion;
import com.careerpilot.dto.ai.InterviewEvaluationAiOutput;
import com.careerpilot.dto.ai.InterviewSummaryAiOutput;
import com.careerpilot.entity.InterviewQuestion;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.mapper.InterviewQuestionMapper;
import com.careerpilot.mapper.InterviewSessionMapper;

/**
 * 面试模块的**写路径**（事务边界集中地）。独立成 bean 是刻意的：
 * {@code @Transactional} 经 Spring 代理生效，若把写方法放在调用方同类中会因自调用失效；
 * 同时它把事务范围限定在"落库段"——**模型调用一律发生在事务之外**
 * （出题 10–40s、评价 10–30s，包进事务会长时间占用连接并持有行锁）。
 *
 * <p>与 M1–M5 的差异（《面试模块》是全项目首个多行原子写入的模块）：
 * 既有的分析类写路径都是单条 INSERT、天然原子，故不加事务；本模块的创建会话是
 * 「会话 + N 条题目」多行写入，必须同一事务，任一步失败整体回滚（不产生空会话）。
 */
@Service
public class InterviewTxService {

    private static final Logger log = LoggerFactory.getLogger(InterviewTxService.class);

    /** 总评来源：模型生成成功（M15，《数据库设计》5.14） */
    public static final String SUMMARY_SOURCE_AI = "AI";

    /** 总评来源：模型失败后的系统降级汇总（M15） */
    public static final String SUMMARY_SOURCE_DEGRADED = "DEGRADED";

    private final InterviewSessionMapper sessionMapper;

    private final InterviewQuestionMapper questionMapper;

    public InterviewTxService(InterviewSessionMapper sessionMapper,
                              InterviewQuestionMapper questionMapper) {
        this.sessionMapper = sessionMapper;
        this.questionMapper = questionMapper;
    }

    // ---------------------------------------------------------------- 创建会话

    /**
     * 保存会话与其全部题目（**同一事务**）：中途任何异常整体回滚，不产生空会话。
     *
     * @return 落库后的题目实体（含回填的 id，供创建响应装配）
     */
    @Transactional
    public List<InterviewQuestion> saveNewSession(InterviewSession session, List<GeneratedQuestion> questions) {
        sessionMapper.insert(session);
        List<InterviewQuestion> saved = new ArrayList<>(questions.size());
        int sortOrder = 1;
        for (GeneratedQuestion question : questions) {
            InterviewQuestion entity = new InterviewQuestion();
            entity.setSessionId(session.getId());
            entity.setQuestion(question.getQuestion());
            entity.setQuestionCategory(question.getQuestionCategory());
            // M15：知识点摘要（清洗后可能为 null——读取方回退题干前 30 字）
            entity.setTopicSummary(question.getTopicSummary());
            entity.setSortOrder(sortOrder++);
            // 显式设置时间：MP 的 insert 不回读数据库默认值，且裁到秒与 DATETIME(0) 对齐
            entity.setCreatedAt(session.getCreatedAt());
            entity.setUpdatedAt(session.getCreatedAt());
            questionMapper.insert(entity);
            saved.add(entity);
        }
        log.info("面试会话已落库：sessionId={} 计划题数={} 实际题数={}",
                session.getId(), session.getQuestionCount(), saved.size());
        return saved;
    }

    // ---------------------------------------------------------------- 提交回答

    /**
     * 写入回答与评价，并在会话完成时计算总分（**同一事务**）。
     *
     * <p>并发与一致性（本方法的关键设计）：
     * <ol>
     *   <li>先对**会话行**加锁（{@code FOR UPDATE}）序列化同一会话的并发提交；</li>
     *   <li>更新题目**必须用实体方式**（{@code mapper.update(entity, wrapper)}）——
     *       wrapper 的 {@code .set()} 路径不携带 {@code @TableField} 上的 TypeHandler，
     *       {@code suggestions}（JSON 列）会被写坏；</li>
     *   <li>重算的读取用**当前读**（{@code FOR UPDATE}）：MySQL 默认 REPEATABLE READ 下
     *       普通 SELECT 走事务首次快照，并发提交（同会话不同题）会看不到对方刚写入的分数、
     *       算出错误总分；锁序恒为「会话行 → 题目行」，无死锁面。</li>
     * </ol>
     *
     * @return 本次提交后的完成状态与全部题目（总评提示词的事实基准）
     */
    @Transactional
    public AnswerOutcome applyAnswerAndRecompute(Long userId, Long sessionId, Long questionId,
                                                 String answer, InterviewEvaluationAiOutput evaluation) {
        // ① 会话行锁 + 当前读（同时复核归属，理论上前置校验已通过，此处为锁内防御）
        InterviewSession session = sessionMapper.selectOne(Wrappers.<InterviewSession>lambdaQuery()
                .eq(InterviewSession::getId, sessionId)
                .eq(InterviewSession::getUserId, userId)
                .last("FOR UPDATE"));
        if (session == null) {
            throw BusinessException.notFound("面试会话不存在");
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);

        // ② 题目归属复核（题目必须属于路径中的会话）
        InterviewQuestion existing = questionMapper.selectOne(Wrappers.<InterviewQuestion>lambdaQuery()
                .eq(InterviewQuestion::getId, questionId)
                .eq(InterviewQuestion::getSessionId, sessionId));
        if (existing == null) {
            throw BusinessException.notFound("面试题目不存在");
        }

        // ③ 覆盖写入回答与评价（实体方式，保证 JSON 列走 TypeHandler）
        //    重复提交即覆盖，得分以最后一次为准（幂等，《API接口设计》§7.2）
        InterviewQuestion patch = new InterviewQuestion();
        patch.setAnswer(answer);
        patch.setScore(evaluation.getScore());
        patch.setAnalysis(evaluation.getAnalysis());
        patch.setReferenceAnswer(evaluation.getReferenceAnswer());
        // 建议为空时写空数组而非保留 null：MP 的实体更新跳过 null 字段，
        // 重答场景下若模型未给建议，显式空数组才能覆盖上一次的旧建议
        patch.setSuggestions(evaluation.getSuggestions() == null ? List.of() : evaluation.getSuggestions());
        patch.setUpdatedAt(now);
        questionMapper.update(patch, Wrappers.<InterviewQuestion>lambdaUpdate()
                .eq(InterviewQuestion::getId, questionId)
                .eq(InterviewQuestion::getSessionId, sessionId));

        // ④ 当前读全部题目，判定完成并重算总分
        List<InterviewQuestion> questions = questionMapper.selectList(Wrappers.<InterviewQuestion>lambdaQuery()
                .eq(InterviewQuestion::getSessionId, sessionId)
                .orderByAsc(InterviewQuestion::getSortOrder)
                .last("FOR UPDATE"));
        long answered = questions.stream().filter(question -> question.getAnswer() != null).count();
        boolean completed = !questions.isEmpty() && answered == questions.size();

        BigDecimal totalScore = null;
        if (completed) {
            totalScore = averageScore(questions.stream()
                    .map(InterviewQuestion::getScore)
                    .toList());
            InterviewSession update = new InterviewSession();
            update.setId(sessionId);
            update.setTotalScore(totalScore);
            update.setStatus(InterviewSession.STATUS_COMPLETED);
            // 显式写 updated_at：MySQL 的 ON UPDATE 在"行值未变化"时不刷新
            // （重答场景下重复置 status=1 属 no-op，须显式刷新时间）
            update.setUpdatedAt(now);
            // completed_at 仅首次完成时写入（重答保持首次值，《数据库设计》§7 语义）
            if (session.getCompletedAt() == null) {
                update.setCompletedAt(now);
            }
            sessionMapper.updateById(update);
            log.info("面试会话已完成：sessionId={} 题数={} 总分={}", sessionId, questions.size(), totalScore);
        }
        return new AnswerOutcome(completed, totalScore, questions);
    }

    // ---------------------------------------------------------------- 总评

    /**
     * 写入总评（第二个短事务）。由 {@link InterviewService} 在模型调用成功后调用；
     * **失败不阻断提交**由调用方负责（本方法只负责写入）。
     *
     * @param source 总评来源（{@link #SUMMARY_SOURCE_AI} / {@link #SUMMARY_SOURCE_DEGRADED}，
     *               M15 起随总评落库——"降级"可见而非静默缺失）
     */
    @Transactional
    public void saveSummary(Long sessionId, InterviewSummaryAiOutput summary, String source) {
        InterviewSession update = new InterviewSession();
        update.setId(sessionId);
        update.setOverallSummary(summary.getSummary());
        // 三个列表用空列表兜底 null（同题目的 suggestions 处理）：重答重新生成时
        // 能覆盖旧值，避免"新总评 + 旧标签"的混搭
        update.setStrengths(summary.getStrengths() == null ? List.of() : summary.getStrengths());
        update.setWeaknesses(summary.getWeaknesses() == null ? List.of() : summary.getWeaknesses());
        update.setRecommendations(summary.getRecommendations() == null
                ? List.of() : summary.getRecommendations());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        update.setSummarySource(source);
        update.setSummaryGeneratedAt(now);
        update.setUpdatedAt(now);
        sessionMapper.updateById(update);
    }

    // ---------------------------------------------------------------- 工具

    /** 题均分（两位小数，HALF_UP）——总分与分类得分共用的唯一计算口径 */
    public static BigDecimal averageScore(List<BigDecimal> scores) {
        BigDecimal sum = scores.stream()
                .filter(score -> score != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long count = scores.stream().filter(score -> score != null).count();
        return count == 0
                ? null
                : sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    /** 提交回答的事务结果：是否完成、总分（未完成时为 null）、全部题目 */
    public record AnswerOutcome(boolean completed, BigDecimal totalScore, List<InterviewQuestion> questions) {
    }

}
