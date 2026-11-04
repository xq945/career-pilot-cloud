package com.careerpilot.service;

import java.util.List;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.AnswerEvaluationResponse;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewDetail;
import com.careerpilot.dto.InterviewReport;
import com.careerpilot.dto.InterviewView;
import com.careerpilot.entity.InterviewSession;

/**
 * 面试业务契约（阶段 8）：单体在 {@code app.interview.mode=remote} 时使用
 * {@code career-interview-client} 的远程实现，面试服务内使用 {@link LocalInterviewService}。
 *
 * <p>除 REST 六个入口外，另暴露三个**跨域只读**方法供 Dashboard 聚合使用
 * （阶段 10 的 Dashboard BFF 会替换该依赖；在此之前单体通过内部端点读取计数与最近会话）。
 */
public interface InterviewService {

    /** 创建面试会话（§7.1）：出题（模型，事务外）→ 会话与题目同一事务落库 */
    InterviewCreateResponse create(Long userId, InterviewCreateRequest request);

    /** 提交回答并评价（§7.2）；会话完成时按配置同步或异步生成总评 */
    AnswerEvaluationResponse submitAnswer(Long userId, Long sessionId, Long questionId, String answer);

    /** 重新生成总评（§7.5）：仅已完成会话；失败向上抛出，原总评保持不变 */
    InterviewReport regenerateSummary(Long userId, Long sessionId);

    /** 列表（§7.3）：倒序分页，内联岗位名称与进度计数 */
    PageResult<InterviewView> list(Long userId, PageQuery query);

    /** 详情（§7.3）：会话 + 全部题目 */
    InterviewDetail detail(Long userId, Long id);

    /** 报告（§7.4）：只读已写入的总分与总评 */
    InterviewReport report(Long userId, Long id);

    /** 归属校验：查不到（不存在或属于他人）一律 404 */
    InterviewSession getOwnedSession(Long userId, Long id);

    /** Dashboard：本人会话总数 */
    long countOwnedSessions(Long userId);

    /** Dashboard：本人指定状态的会话数（0 进行中 / 1 已完成） */
    long countOwnedSessionsByStatus(Long userId, int status);

    /** Dashboard：最近 N 场会话（created_at 倒序，id 倒序作 tie-breaker） */
    List<InterviewSession> recentSessions(Long userId, int limit);
}
