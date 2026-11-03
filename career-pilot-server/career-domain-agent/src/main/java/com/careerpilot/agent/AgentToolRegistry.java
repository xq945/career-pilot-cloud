package com.careerpilot.agent;

import java.util.List;

import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import com.careerpilot.agent.tools.InterviewAgentTools;
import com.careerpilot.agent.tools.JobAgentTools;
import com.careerpilot.agent.tools.MatchAgentTools;
import com.careerpilot.agent.tools.ResumeAgentTools;
import com.careerpilot.ai.AgentToolCallRecorder;
import com.careerpilot.ai.RecordingToolCallback;

/**
 * Agent 工具注册表：把四个 {@code @Tool} bean 转换为 {@code ToolCallback} 并集中收口
 * （《Agent设计》§3 的工具清单、§7.5 的包装方案）。
 *
 * <p>收口在单一位置的好处：JSON Schema 可在此整体断言（"参数中不出现 userId"），
 * 包装逻辑只实现一处，工具名清单可直接用于日志与验收。
 *
 * <p>注意：工具 bean **不实现** {@code ToolCallback}/{@code ToolCallbackProvider}，
 * 以免被框架的 {@code ToolCallbackResolver} 注册为全局工具、污染 M4–M7 的结构化输出调用；
 * 回调仅在每次对话请求时经 {@link #wrap} 现场构建。
 */
@Component
public class AgentToolRegistry {

    private final List<ToolCallback> rawCallbacks;

    private final List<String> toolNames;

    public AgentToolRegistry(ResumeAgentTools resumeTools, JobAgentTools jobTools,
                             MatchAgentTools matchTools, InterviewAgentTools interviewTools) {
        this.rawCallbacks = List.of(
                ToolCallbacks.from(resumeTools, jobTools, matchTools, interviewTools));
        this.toolNames = rawCallbacks.stream()
                .map(callback -> callback.getToolDefinition().name())
                .toList();
    }

    /** 为单次对话请求包装出记录型回调（每请求一份，随请求丢弃） */
    public List<ToolCallback> wrap(AgentToolCallRecorder recorder) {
        return rawCallbacks.stream()
                .map(callback -> (ToolCallback) new RecordingToolCallback(callback, recorder))
                .toList();
    }

    /** 工具名清单（日志与 Schema 断言用；顺序即构造顺序） */
    public List<String> toolNames() {
        return toolNames;
    }

}
