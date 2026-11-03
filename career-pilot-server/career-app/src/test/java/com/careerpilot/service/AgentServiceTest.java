package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.UserMessage;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.careerpilot.agent.AgentToolRegistry;
import com.careerpilot.ai.AgentCallResult;
import com.careerpilot.ai.AgentToolCallRecorder;
import com.careerpilot.ai.RecordedToolCall;
import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.common.PageResult;
import com.careerpilot.config.AgentProperties;
import com.careerpilot.dto.AgentChatRequest;
import com.careerpilot.dto.AgentChatResponse;
import com.careerpilot.dto.AgentMessageView;
import com.careerpilot.dto.AgentSessionDetail;
import com.careerpilot.dto.AgentSessionView;
import com.careerpilot.entity.AgentChatMessage;
import com.careerpilot.entity.AgentSession;
import com.careerpilot.entity.Job;
import com.careerpilot.entity.Resume;
import com.careerpilot.mapper.AgentChatMessageMapper;
import com.careerpilot.mapper.AgentSessionMapper;
import com.careerpilot.service.ai.AgentAiService;

/**
 * 智能体编排的护栏（《API接口设计》§8.1–§8.3）：
 * 归属 404 先于模型调用（AG-03）、502 不建会话不落消息（AG-05）、越限照常落库且
 * truncated=true（AG-12）、标题规则、会话列表排序与消息数、§8.3 工具消息只给名与状态。
 */
class AgentServiceTest {

    private AgentSessionMapper sessionMapper;

    private AgentChatMessageMapper messageMapper;

    private AgentAiService aiService;

    private AgentTxService txService;

    private AgentToolRegistry registry;

    private ResumeService resumeService;

    private JobService jobService;

    private ResumeAnalysisService resumeAnalysisService;

    private JobAnalysisService jobAnalysisService;

    private AgentService service;

    @BeforeAll
    static void initTableInfo() {
        // MyBatis-Plus 的 lambda 列名解析依赖 TableInfo 缓存；纯单测没有 Spring 上下文，
        // 需手动初始化，否则列表计数路径报 "can not find lambda cache for this entity"
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, AgentSession.class);
        TableInfoHelper.initTableInfo(assistant, AgentChatMessage.class);
    }

    @BeforeEach
    void setUp() {
        sessionMapper = mock(AgentSessionMapper.class);
        messageMapper = mock(AgentChatMessageMapper.class);
        aiService = mock(AgentAiService.class);
        txService = mock(AgentTxService.class);
        registry = mock(AgentToolRegistry.class);
        resumeService = mock(ResumeService.class);
        jobService = mock(JobService.class);
        resumeAnalysisService = mock(ResumeAnalysisService.class);
        jobAnalysisService = mock(JobAnalysisService.class);
        service = new AgentService(sessionMapper, messageMapper, aiService, txService, registry,
                new AgentProperties(120_000, 5, 10, 6000, 20),
                resumeService, jobService, resumeAnalysisService, jobAnalysisService);

        // 通用桩：清单为空、提示词组装走桩、无工具回调
        when(resumeService.list(eq(7L), any()))
                .thenReturn(PageResult.of(new Page<>(1, 20), List.of()));
        when(jobService.list(eq(7L), any()))
                .thenReturn(PageResult.of(new Page<>(1, 20), List.of()));
        when(aiService.buildUserMessage(any(), any(), any(), any(), any())).thenReturn("用户消息文本");
        when(aiService.buildConversation(any(), any()))
                .thenReturn(List.of(new UserMessage("用户消息文本")));
        when(registry.wrap(any())).thenReturn(List.of());
    }

    // ---------------------------------------------------------------- 护栏（§8.1）

    @Test
    void chat_contextResumeNotOwned_404AndModelNotCalledAndNothingPersisted() {
        when(resumeService.getOwnedResume(7L, 99L))
                .thenThrow(BusinessException.notFound("简历不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chat(7L, request("帮我分析简历", null, 99L, null)));

        assertEquals(40400, ex.getCode());
        // 归属校验先于模型调用：不烧 token、不建会话、不写消息（AG-03）
        verifyNoInteractions(aiService, txService);
        verify(sessionMapper, never()).insert(isA(AgentSession.class));
    }

    @Test
    void chat_contextJobNotOwned_404AndModelNotCalled() {
        when(jobService.getOwnedJob(7L, 99L)).thenThrow(BusinessException.notFound("岗位不存在"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chat(7L, request("看看这个岗位", null, null, 99L)));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void chat_sessionNotOwned_404() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chat(7L, request("继续", "sid-other", null, null)));

        assertEquals(40400, ex.getCode());
        verifyNoInteractions(aiService, txService);
    }

    @Test
    void chat_modelFails_502AndNoSessionNoMessages() {
        when(aiService.chat(any(), any()))
                .thenThrow(BusinessException.aiUnavailable("大模型服务暂时不可用"));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.chat(7L, request("帮我分析简历", null, null, null)));

        assertEquals(50200, ex.getCode());
        // 整轮成功才落库：502 时不创建会话、不写入任何消息（AG-05）
        verifyNoInteractions(txService);
        verify(sessionMapper, never()).insert(isA(AgentSession.class));
        verify(messageMapper, never()).insert(isA(AgentChatMessage.class));
    }

    @Test
    void chat_firstTurn_persistsTurnWithTitleFromFirstMessage() {
        when(aiService.chat(any(), any()))
                .thenReturn(new AgentCallResult("这是回答", "deepseek-flash", 1500L, 10, 20, 30));

        AgentChatResponse response = service.chat(7L,
                request("  帮我看看这份简历\n适不适合这个岗位  ", null, null, null));

        assertEquals(32, response.getSessionId().length(), "会话标识为 UUID 去连字符");
        // 落库的消息正文只 trim 首尾；标题额外折叠空白
        ArgumentCaptor<AgentSession> sessionCaptor = ArgumentCaptor.forClass(AgentSession.class);
        verify(txService).saveTurn(sessionCaptor.capture(), eq(true), eq(7L),
                eq("帮我看看这份简历\n适不适合这个岗位"), anyList(), eq("这是回答"), any(), any());
        assertEquals("帮我看看这份简历 适不适合这个岗位", sessionCaptor.getValue().getTitle());
        assertEquals(7L, sessionCaptor.getValue().getUserId());
        assertEquals(response.getSessionId(), sessionCaptor.getValue().getSessionId());
        assertTrue(response.getToolCalls().isEmpty());
    }

    @Test
    void chat_limitRejected_truncatedTrueAndTurnPersistedWithToolCalls() {
        // 模拟包装器：记录一次成功的工具调用后触发次数上限
        when(registry.wrap(any())).thenAnswer(invocation -> {
            AgentToolCallRecorder recorder = invocation.getArgument(0);
            recorder.record(new RecordedToolCall("analyzeResume", "{\"resumeId\":12}",
                    "SUCCESS", "简历分析完成", 800L, LocalDateTime.now().withNano(0)));
            recorder.markLimitRejected();
            return List.of();
        });
        when(aiService.chat(any(), any()))
                .thenReturn(new AgentCallResult("已达到工具调用上限，已完成部分如下…", "deepseek-flash",
                        30_000L, 10, 20, 30));

        AgentChatResponse response = service.chat(7L, request("分析我的全部简历和岗位", null, null, null));

        // AG-12：truncated=true、toolCalls ≤ 5，且该轮消息照常落库（R05）
        assertTrue(response.isTruncated());
        assertEquals(1, response.getToolCalls().size());
        assertEquals("analyzeResume", response.getToolCalls().get(0).getToolName());
        verify(txService).saveTurn(any(), anyBoolean(), eq(7L), any(), anyList(), any(), any(), any());
    }

    // ---------------------------------------------------------------- 列表与回看（§8.2/§8.3）

    @Test
    void listSessions_orderedByUpdatedAtDesc_withMessageCount() {
        AgentSession newer = session("sid-new", "新会话", LocalDateTime.of(2026, 10, 10, 15, 0));
        AgentSession older = session("sid-old", "旧会话", LocalDateTime.of(2026, 10, 10, 11, 0));
        Page<AgentSession> page = new Page<>(1, 10);
        page.setRecords(List.of(newer, older));
        page.setTotal(2);
        when(sessionMapper.selectPage(any(), any())).thenReturn(page);

        AgentChatMessage first = new AgentChatMessage();
        first.setSessionId("sid-new");
        AgentChatMessage second = new AgentChatMessage();
        second.setSessionId("sid-new");
        AgentChatMessage third = new AgentChatMessage();
        third.setSessionId("sid-old");
        when(messageMapper.selectList(any())).thenReturn(List.of(first, second, third));

        PageResult<AgentSessionView> result = service.listSessions(7L, new PageQuery());

        assertEquals(2, result.getRecords().size());
        assertEquals("sid-new", result.getRecords().get(0).getSessionId());
        assertEquals(2, result.getRecords().get(0).getMessageCount());
        assertEquals(1, result.getRecords().get(1).getMessageCount());
        assertEquals(2L, result.getTotal());
    }

    @Test
    void detail_toolMessage_exposesNameAndStatusOnly() {
        when(sessionMapper.selectOne(any())).thenReturn(session("sid-1", "会话", LocalDateTime.now()));

        AgentChatMessage user = new AgentChatMessage();
        user.setRole(AgentChatMessage.ROLE_USER);
        user.setContent("帮我分析简历");
        user.setCreatedAt(LocalDateTime.of(2026, 10, 10, 12, 0));
        AgentChatMessage tool = new AgentChatMessage();
        tool.setRole(AgentChatMessage.ROLE_TOOL);
        tool.setToolName("analyzeResume");
        tool.setToolArguments(java.util.Map.of("resumeId", 12));
        tool.setToolResult(new com.careerpilot.dto.AgentToolResultPayload("SUCCESS", 800L, "简历分析完成"));
        tool.setCreatedAt(LocalDateTime.of(2026, 10, 10, 12, 0, 5));
        AgentChatMessage assistant = new AgentChatMessage();
        assistant.setRole(AgentChatMessage.ROLE_ASSISTANT);
        assistant.setContent("分析结果如下");
        assistant.setCreatedAt(LocalDateTime.of(2026, 10, 10, 12, 0, 10));
        when(messageMapper.selectList(any())).thenReturn(List.of(user, tool, assistant));

        AgentSessionDetail detail = service.detail(7L, "sid-1");

        assertEquals(3, detail.getMessages().size());
        AgentMessageView toolView = detail.getMessages().get(1);
        assertEquals("analyzeResume", toolView.getToolName());
        assertEquals("SUCCESS", toolView.getStatus());
        // §8.3：工具消息不返回原始入参与结果
        assertNull(toolView.getContent());
        assertNull(detail.getMessages().get(0).getToolName());
        assertEquals("帮我分析简历", detail.getMessages().get(0).getContent());
    }

    @Test
    void detail_sessionNotOwned_404() {
        when(sessionMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.detail(7L, "sid-other"));

        assertEquals(40400, ex.getCode());
    }

    // ---------------------------------------------------------------- 工具

    private static AgentChatRequest request(String message, String sessionId, Long resumeId, Long jobId) {
        AgentChatRequest request = new AgentChatRequest();
        request.setMessage(message);
        request.setSessionId(sessionId);
        request.setResumeId(resumeId);
        request.setJobId(jobId);
        return request;
    }

    private static AgentSession session(String sessionId, String title, LocalDateTime updatedAt) {
        AgentSession session = new AgentSession();
        session.setUserId(7L);
        session.setSessionId(sessionId);
        session.setTitle(title);
        session.setCreatedAt(updatedAt.minusHours(1));
        session.setUpdatedAt(updatedAt);
        return session;
    }

}
