package com.careerpilot.service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.agent.AgentToolRegistry;
import com.careerpilot.ai.AgentCallResult;
import com.careerpilot.ai.AgentToolBudget;
import com.careerpilot.ai.AgentToolCallRecorder;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.config.AgentProperties;
import com.careerpilot.dto.AgentChatRequest;
import com.careerpilot.dto.AgentChatResponse;
import com.careerpilot.dto.AgentMessageView;
import com.careerpilot.dto.AgentSessionDetail;
import com.careerpilot.dto.AgentSessionView;
import com.careerpilot.dto.AgentToolCallView;
import com.careerpilot.dto.JobView;
import com.careerpilot.dto.ResumeView;
import com.careerpilot.entity.AgentChatMessage;
import com.careerpilot.entity.AgentSession;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.JobAnalysis;
import com.careerpilot.entity.Resume;
import com.careerpilot.entity.ResumeAnalysis;
import com.careerpilot.mapper.AgentChatMessageMapper;
import com.careerpilot.mapper.AgentSessionMapper;
import com.careerpilot.service.ai.AgentAiService;

/**
 * 求职智能体业务编排（《API接口设计》§8.1–§8.3；《Agent设计》§2–§8）。
 *
 * <p>本类**不加事务**：写路径委托 {@link AgentTxService}（独立 bean，代理生效），
 * 且模型调用一律发生在事务之外——502 先于任何写入抛出，天然满足"整轮成功才落库"
 * （不建会话、不写任何消息，既有会话历史不受影响）。
 *
 * <p>归属校验分两层（AG-03）：
 * <ol>
 *   <li>请求体上的 sessionId/resumeId/jobId 在**模型调用前**校验（非本人/不存在/已删除一律 404），
 *       不烧 token、不建会话；</li>
 *   <li>模型把他人 ID 塞进工具参数时由工具内的 {@code getOwned*} 校验拦下，
 *       转为失败信封交给模型说明（不泄露数据、无存在性泄露）。</li>
 * </ol>
 */
@Service
public class AgentService {

    /** 会话标题上限（agent_session.title 为 VARCHAR(100)） */
    private static final int TITLE_LIMIT = 100;

    private final AgentSessionMapper agentSessionMapper;

    private final AgentChatMessageMapper agentChatMessageMapper;

    private final AgentAiService agentAiService;

    private final AgentTxService agentTxService;

    private final AgentToolRegistry agentToolRegistry;

    private final AgentProperties agentProperties;

    private final ResumeService resumeService;

    private final JobService jobService;

    private final ResumeAnalysisService resumeAnalysisService;

    private final JobAnalysisService jobAnalysisService;

    public AgentService(AgentSessionMapper agentSessionMapper,
                        AgentChatMessageMapper agentChatMessageMapper,
                        AgentAiService agentAiService,
                        AgentTxService agentTxService,
                        AgentToolRegistry agentToolRegistry,
                        AgentProperties agentProperties,
                        ResumeService resumeService,
                        JobService jobService,
                        ResumeAnalysisService resumeAnalysisService,
                        JobAnalysisService jobAnalysisService) {
        this.agentSessionMapper = agentSessionMapper;
        this.agentChatMessageMapper = agentChatMessageMapper;
        this.agentAiService = agentAiService;
        this.agentTxService = agentTxService;
        this.agentToolRegistry = agentToolRegistry;
        this.agentProperties = agentProperties;
        this.resumeService = resumeService;
        this.jobService = jobService;
        this.resumeAnalysisService = resumeAnalysisService;
        this.jobAnalysisService = jobAnalysisService;
    }

    // ---------------------------------------------------------------- 对话（§8.1）

    /**
     * 发起一轮对话：预校验 → 组装 → 模型调用（事务外）→ 整轮落库（同一事务）→ 响应。
     *
     * <p>工具次数（5）与总时长（120 秒）上限由 {@link AgentToolCallRecorder} /
     * {@link AgentToolBudget} 在工具执行边界执行；越限时模型基于已有结果收尾，
     * 响应置 {@code truncated = true}（该轮消息**照常落库**，R05）。
     */
    public AgentChatResponse chat(Long userId, AgentChatRequest request) {
        boolean firstTurn = request.getSessionId() == null || request.getSessionId().isBlank();
        // 归属预校验先于一切模型调用
        AgentSession session = firstTurn ? null : getOwnedSession(userId, request.getSessionId());
        String sessionId = firstTurn ? generateSessionId() : session.getSessionId();

        Resume contextResume = request.getResumeId() == null
                ? null : resumeService.getOwnedResume(userId, request.getResumeId());
        Job contextJob = request.getJobId() == null
                ? null : jobService.getOwnedJob(userId, request.getJobId());

        // 历史：只回放 user/assistant 文本（裁剪规则见 AgentAiService）
        List<AgentChatMessage> pastMessages = firstTurn ? List.of() : listMessages(sessionId);

        String message = request.getMessage().trim();
        String userPrompt = agentAiService.buildUserMessage(
                resumeCatalog(userId), jobCatalog(userId),
                contextResume == null ? null : resumeBrief(contextResume),
                contextJob == null ? null : jobBrief(contextJob),
                message);
        List<Message> conversation = agentAiService.buildConversation(pastMessages, userPrompt);

        // 工具与预算：每请求一份，随请求丢弃
        AgentToolBudget budget = new AgentToolBudget(agentProperties.totalTimeoutMillis());
        AgentToolCallRecorder recorder =
                new AgentToolCallRecorder(budget, agentProperties.maxToolCalls());
        List<ToolCallback> toolCallbacks = agentToolRegistry.wrap(recorder);

        // 模型调用（内含任意次模型回合与工具执行，事务之外）：失败 502 先于任何写入抛出
        LocalDateTime userMessageAt = LocalDateTime.now().withNano(0);
        AgentCallResult result = agentAiService.chat(conversation, toolCallbacks);
        LocalDateTime assistantMessageAt = LocalDateTime.now().withNano(0);

        if (firstTurn) {
            session = newSession(userId, sessionId, message, userMessageAt, assistantMessageAt);
        }
        agentTxService.saveTurn(session, firstTurn, userId, message,
                recorder.calls(), result.message(), userMessageAt, assistantMessageAt);

        AgentChatResponse response = new AgentChatResponse();
        response.setSessionId(sessionId);
        response.setMessage(result.message());
        response.setTruncated(recorder.truncated());
        response.setToolCalls(recorder.calls().stream()
                .map(call -> new AgentToolCallView(call.toolName(), call.status()))
                .toList());
        return response;
    }

    /** 首轮会话元数据（随"整轮成功"的同一事务落库） */
    private static AgentSession newSession(Long userId, String sessionId, String message,
                                           LocalDateTime createdAt, LocalDateTime updatedAt) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        session.setSessionId(sessionId);
        session.setTitle(titleOf(message));
        session.setCreatedAt(createdAt);
        session.setUpdatedAt(updatedAt);
        return session;
    }

    /** 会话标题：首条用户消息折叠空白后截断 100（《数据库设计》表注释） */
    private static String titleOf(String message) {
        String flat = message.replaceAll("\\s+", " ").trim();
        return flat.length() > TITLE_LIMIT ? flat.substring(0, TITLE_LIMIT) : flat;
    }

    /** 会话标识：UUID 去连字符（32 字符，VARCHAR(64) 富余），不暴露自增 id */
    private static String generateSessionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ---------------------------------------------------------------- 列表与回看（§8.2/§8.3）

    /** 会话列表：按 updated_at 倒序（id 倒序作稳定 tie-breaker），内联消息数 */
    public PageResult<AgentSessionView> listSessions(Long userId, PageQuery query) {
        Page<AgentSession> page = new Page<>(query.getPage(), query.getSize());
        IPage<AgentSession> result = agentSessionMapper.selectPage(page,
                Wrappers.<AgentSession>lambdaQuery()
                        .eq(AgentSession::getUserId, userId)
                        .orderByDesc(AgentSession::getUpdatedAt)
                        .orderByDesc(AgentSession::getId));

        List<AgentSession> sessions = result.getRecords();
        Map<String, Integer> counts = countMessagesBySessionIds(sessions);

        List<AgentSessionView> records = sessions.stream().map(session -> {
            AgentSessionView view = new AgentSessionView();
            view.setSessionId(session.getSessionId());
            view.setTitle(session.getTitle());
            view.setMessageCount(counts.getOrDefault(session.getSessionId(), 0));
            view.setCreatedAt(session.getCreatedAt());
            view.setUpdatedAt(session.getUpdatedAt());
            return view;
        }).toList();
        return PageResult.of(result, records);
    }

    /** 消息回看：按 created_at 升序（id 升序兜底），工具消息只给工具名与状态 */
    public AgentSessionDetail detail(Long userId, String sessionId) {
        AgentSession session = getOwnedSession(userId, sessionId);

        AgentSessionDetail detail = new AgentSessionDetail();
        detail.setSessionId(session.getSessionId());
        detail.setTitle(session.getTitle());
        detail.setMessages(listMessages(sessionId).stream().map(AgentService::toMessageView).toList());
        return detail;
    }

    /** §8.3：tool 消息只暴露工具名与状态（status 取自 tool_result 载荷），绝不返回原始入参与结果 */
    private static AgentMessageView toMessageView(AgentChatMessage message) {
        AgentMessageView view = new AgentMessageView();
        view.setRole(message.getRole());
        view.setContent(message.getContent());
        if (AgentChatMessage.ROLE_TOOL.equals(message.getRole())) {
            view.setToolName(message.getToolName());
            view.setStatus(message.getToolResult() == null ? null : message.getToolResult().getStatus());
        }
        view.setCreatedAt(message.getCreatedAt());
        return view;
    }

    // ---------------------------------------------------------------- 归属校验与查询

    /**
     * 会话归属校验：查不到（不存在或属于他人）一律 404，不区分"不存在"与"无权访问"
     * （《API接口设计》§1/A01）。
     */
    public AgentSession getOwnedSession(Long userId, String sessionId) {
        AgentSession session = agentSessionMapper.selectOne(Wrappers.<AgentSession>lambdaQuery()
                .eq(AgentSession::getSessionId, sessionId)
                .eq(AgentSession::getUserId, userId));
        if (session == null) {
            throw BusinessException.notFound("会话不存在");
        }
        return session;
    }

    /** 会话的全部消息（历史装载与回看共用；工具消息由各自的使用方按需过滤/投影） */
    private List<AgentChatMessage> listMessages(String sessionId) {
        return agentChatMessageMapper.selectList(Wrappers.<AgentChatMessage>lambdaQuery()
                .eq(AgentChatMessage::getSessionId, sessionId)
                .orderByAsc(AgentChatMessage::getCreatedAt)
                .orderByAsc(AgentChatMessage::getId));
    }

    /** 当页会话的消息数（两次查询、内存聚合，无 N+1：计数含 user/assistant/tool 三类） */
    private Map<String, Integer> countMessagesBySessionIds(List<AgentSession> sessions) {
        List<String> sessionIds = sessions.stream().map(AgentSession::getSessionId).toList();
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        List<AgentChatMessage> messages = agentChatMessageMapper.selectList(
                Wrappers.<AgentChatMessage>lambdaQuery()
                        .select(AgentChatMessage::getSessionId)
                        .in(AgentChatMessage::getSessionId, sessionIds));
        Map<String, Integer> counts = new HashMap<>();
        for (AgentChatMessage message : messages) {
            counts.merge(message.getSessionId(), 1, Integer::sum);
        }
        return counts;
    }

    // ---------------------------------------------------------------- 提示词上下文数据

    /** 简历清单（只给 ID/名称/分析状态，最多 catalogLimit 条；正文不注入提示词） */
    private List<AgentAiService.ResourceBrief> resumeCatalog(Long userId) {
        PageQuery query = new PageQuery();
        query.setSize(agentProperties.catalogLimit());
        PageResult<ResumeView> result = resumeService.list(userId, query);
        return result.getRecords().stream()
                .map(view -> new AgentAiService.ResourceBrief(view.getId(), view.getName(),
                        view.isAnalyzed() ? "已分析" : "未分析"))
                .toList();
    }

    /** 岗位清单（只给 ID/名称/公司/分析状态） */
    private List<AgentAiService.ResourceBrief> jobCatalog(Long userId) {
        PageQuery query = new PageQuery();
        query.setSize(agentProperties.catalogLimit());
        PageResult<JobView> result = jobService.list(userId, query);
        return result.getRecords().stream()
                .map(view -> new AgentAiService.ResourceBrief(view.getId(),
                        label(view.getJobName(), view.getCompanyName()),
                        jobState(view.isAnalyzed(), view.isStale())))
                .toList();
    }

    /** 上下文简历的状态（含版本号，精确查询） */
    private AgentAiService.ResourceBrief resumeBrief(Resume resume) {
        ResumeAnalysis analysis = resumeAnalysisService.findLatestSuccess(resume.getId());
        return new AgentAiService.ResourceBrief(resume.getId(), resume.getName(),
                analysis == null ? "未分析" : "已分析，版本 v" + analysis.getVersionNo());
    }

    /** 上下文岗位的状态（含过期判定，与列表口径一致） */
    private AgentAiService.ResourceBrief jobBrief(Job job) {
        JobAnalysis analysis = jobAnalysisService.findLatestSuccess(job.getId());
        String state;
        if (analysis == null) {
            state = "未分析";
        }
        else if (JobAnalysisService.isStale(job.getDescription(), analysis)) {
            state = "分析已过期（岗位描述在分析后更新过）";
        }
        else {
            state = "已分析，版本 v" + analysis.getVersionNo();
        }
        return new AgentAiService.ResourceBrief(job.getId(),
                label(job.getJobName(), job.getCompanyName()), state);
    }

    private static String jobState(boolean analyzed, boolean stale) {
        if (!analyzed) {
            return "未分析";
        }
        return stale ? "分析已过期（岗位描述在分析后更新过）" : "已分析";
    }

    private static String label(String name, String company) {
        if (company == null || company.isBlank()) {
            return name;
        }
        return name + " @ " + company;
    }

}
