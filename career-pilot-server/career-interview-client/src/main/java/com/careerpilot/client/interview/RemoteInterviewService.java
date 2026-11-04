package com.careerpilot.client.interview;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.dto.AnswerEvaluationResponse;
import com.careerpilot.dto.AnswerSubmitRequest;
import com.careerpilot.dto.InterviewCreateRequest;
import com.careerpilot.dto.InterviewCreateResponse;
import com.careerpilot.dto.InterviewDetail;
import com.careerpilot.dto.InterviewReport;
import com.careerpilot.dto.InterviewView;
import com.careerpilot.entity.InterviewSession;
import com.careerpilot.service.InterviewService;

/**
 * InterviewService 的远程实现（app.interview.mode=remote）。
 *
 * <p>公开 CRUD 走 /api/interviews（响应解包 {@code ApiResponse}）；跨域只读走
 * /internal/interviews（裸 DTO）。身份由 Feign 拦截器按目标服务签发内部令牌。
 */
@Service
@ConditionalOnProperty(prefix = "app.interview", name = "mode", havingValue = "remote")
public class RemoteInterviewService implements InterviewService {

    private final InterviewPublicClient publicClient;

    private final InterviewInternalClient internalClient;

    public RemoteInterviewService(InterviewPublicClient publicClient,
                                  InterviewInternalClient internalClient) {
        this.publicClient = publicClient;
        this.internalClient = internalClient;
    }

    @Override
    public InterviewCreateResponse create(Long userId, InterviewCreateRequest request) {
        return publicClient.create(request).getData();
    }

    @Override
    public AnswerEvaluationResponse submitAnswer(Long userId, Long sessionId, Long questionId,
                                                 String answer) {
        AnswerSubmitRequest request = new AnswerSubmitRequest();
        request.setAnswer(answer);
        return publicClient.answer(sessionId, questionId, request).getData();
    }

    @Override
    public InterviewReport regenerateSummary(Long userId, Long sessionId) {
        return publicClient.regenerateSummary(sessionId).getData();
    }

    @Override
    public PageResult<InterviewView> list(Long userId, PageQuery query) {
        return publicClient.list(query.getPage(), query.getSize()).getData();
    }

    @Override
    public InterviewDetail detail(Long userId, Long id) {
        return publicClient.detail(id).getData();
    }

    @Override
    public InterviewReport report(Long userId, Long id) {
        return publicClient.report(id).getData();
    }

    @Override
    public InterviewSession getOwnedSession(Long userId, Long id) {
        return internalClient.getOwned(id);
    }

    @Override
    public long countOwnedSessions(Long userId) {
        return internalClient.countOwned();
    }

    @Override
    public long countOwnedSessionsByStatus(Long userId, int status) {
        return internalClient.countOwnedByStatus(status);
    }

    @Override
    public List<InterviewSession> recentSessions(Long userId, int limit) {
        return internalClient.recent(limit);
    }
}
