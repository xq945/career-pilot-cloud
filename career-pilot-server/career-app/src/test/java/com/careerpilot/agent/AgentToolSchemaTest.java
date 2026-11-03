package com.careerpilot.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;

import com.careerpilot.agent.tools.InterviewAgentTools;
import com.careerpilot.agent.tools.JobAgentTools;
import com.careerpilot.agent.tools.MatchAgentTools;
import com.careerpilot.agent.tools.ResumeAgentTools;
import com.careerpilot.ai.AgentToolBudget;
import com.careerpilot.ai.AgentToolCallRecorder;
import com.careerpilot.service.InterviewService;
import com.careerpilot.service.JobAnalysisService;
import com.careerpilot.service.JobService;
import com.careerpilot.service.MatchService;
import com.careerpilot.service.ResumeAnalysisService;
import com.careerpilot.service.ResumeService;

/**
 * 工具 JSON Schema 的静态断言（《Agent设计》§4.1 的硬约束、《开发顺序》M8 验收
 * "工具方法列表中不出现 userId 类参数"）。
 *
 * <p>工具的 Schema 只在模型调用时可见、HTTP 层拿不到，因此这条验收以单测为落点
 * （验证脚本 m8-agent-api.mjs 中留有指向本测试的注释）。
 */
class AgentToolSchemaTest {

    private AgentToolRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new AgentToolRegistry(
                new ResumeAgentTools(mock(ResumeService.class), mock(ResumeAnalysisService.class)),
                new JobAgentTools(mock(JobService.class), mock(JobAnalysisService.class)),
                new MatchAgentTools(mock(ResumeService.class), mock(JobService.class),
                        mock(ResumeAnalysisService.class), mock(JobAnalysisService.class),
                        mock(MatchService.class)),
                new InterviewAgentTools(mock(JobService.class), mock(InterviewService.class)));
    }

    @Test
    void toolNames_areExactlyTheFourContractNames() {
        assertEquals(List.of("analyzeResume", "analyzeJob", "matchResumeToJob",
                "generateInterviewQuestions"), registry.toolNames());
    }

    @Test
    void inputSchema_containsNoUserIdentityParameter() {
        for (ToolCallback callback : wrappedCallbacks()) {
            String schema = callback.getToolDefinition().inputSchema();
            assertFalse(schema.contains("userId"), "工具参数不允许出现 userId：" + schema);
            assertFalse(schema.contains("user_id"), "工具参数不允许出现 user_id：" + schema);
            assertFalse(schema.contains("currentUser"), "工具参数不允许出现 currentUser：" + schema);
            assertFalse(schema.contains("sessionId"), "工具参数不允许出现会话标识：" + schema);
        }
    }

    @Test
    void inputSchema_containsOnlyExpectedResourceParameters() {
        String resumeSchema = schemaOf("analyzeResume");
        assertTrue(resumeSchema.contains("resumeId"), resumeSchema);

        String jobSchema = schemaOf("analyzeJob");
        assertTrue(jobSchema.contains("jobId"), jobSchema);

        String matchSchema = schemaOf("matchResumeToJob");
        assertTrue(matchSchema.contains("resumeId") && matchSchema.contains("jobId"), matchSchema);

        String interviewSchema = schemaOf("generateInterviewQuestions");
        assertTrue(interviewSchema.contains("jobId"), interviewSchema);
        assertTrue(interviewSchema.contains("questionType"), interviewSchema);
        assertTrue(interviewSchema.contains("count"), interviewSchema);
    }

    @Test
    void toolDefinitions_haveDescriptions() {
        for (ToolCallback callback : wrappedCallbacks()) {
            String description = callback.getToolDefinition().description();
            assertTrue(description != null && !description.isBlank(),
                    "工具缺少 description（模型据此判断调用时机）：" + callback.getToolDefinition().name());
        }
    }

    /** 包装后的回调：Schema 应与原始工具逐字一致（包装只加记录，不改写定义） */
    private List<ToolCallback> wrappedCallbacks() {
        AgentToolCallRecorder recorder =
                new AgentToolCallRecorder(new AgentToolBudget(60_000), 5);
        return registry.wrap(recorder);
    }

    private String schemaOf(String toolName) {
        return wrappedCallbacks().stream()
                .filter(callback -> toolName.equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow()
                .getToolDefinition()
                .inputSchema();
    }

}
