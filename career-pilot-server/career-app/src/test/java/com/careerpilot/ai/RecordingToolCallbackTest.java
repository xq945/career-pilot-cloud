package com.careerpilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.careerpilot.common.BusinessException;

/**
 * 工具包装器的护栏（《Agent设计》§7.5、《API接口设计》§8.1）：
 * 成功/失败都记录、异常转失败信封不冒泡（AG-10）、次数与时长上限在边界拦截。
 */
class RecordingToolCallbackTest {

    private static final String SUCCESS_PAYLOAD =
            "{\"status\":\"SUCCESS\",\"summary\":\"简历分析完成\",\"data\":{}}";

    @Test
    void call_success_recordsInvocationAndReturnsDelegateResult() {
        AgentToolCallRecorder recorder = recorder(5, 60_000);
        RecordingToolCallback callback = new RecordingToolCallback(
                stub("analyzeResume", SUCCESS_PAYLOAD), recorder);

        String result = callback.call("{\"resumeId\":12}");

        assertEquals(SUCCESS_PAYLOAD, result);
        assertEquals(1, recorder.calls().size());
        RecordedToolCall call = recorder.calls().get(0);
        assertEquals("analyzeResume", call.toolName());
        assertEquals(RecordedToolCall.STATUS_SUCCESS, call.status());
        assertEquals("简历分析完成", call.summary());
        assertEquals("{\"resumeId\":12}", call.argumentsJson());
    }

    @Test
    void call_delegateThrows_returnsFailedPayloadAndRecordsFailed() {
        AgentToolCallRecorder recorder = recorder(5, 60_000);
        RecordingToolCallback callback = new RecordingToolCallback(
                throwing("analyzeResume", BusinessException.notFound("简历不存在")), recorder);

        String result = callback.call("{\"resumeId\":999}");

        // 不冒泡：转为失败信封交给模型说明（AG-10）
        assertTrue(result.contains("\"status\":\"FAILED\""), result);
        assertTrue(result.contains("简历不存在"), result);
        assertEquals(1, recorder.calls().size());
        assertEquals(RecordedToolCall.STATUS_FAILED, recorder.calls().get(0).status());
    }

    @Test
    void call_delegateReturnsFailedEnvelope_recordsFailed() {
        AgentToolCallRecorder recorder = recorder(5, 60_000);
        RecordingToolCallback callback = new RecordingToolCallback(
                stub("generateInterviewQuestions",
                        "{\"status\":\"FAILED\",\"error\":\"INVALID_ARGUMENT\",\"summary\":\"题型非法\"}"),
                recorder);

        callback.call("{}");

        // 工具方法内部返回失败信封（非异常）时，记录同样按 FAILED 计
        assertEquals(RecordedToolCall.STATUS_FAILED, recorder.calls().get(0).status());
    }

    @Test
    void call_limitReached_skipsDelegateAndReturnsLimitPayload() {
        AtomicInteger executions = new AtomicInteger();
        AgentToolCallRecorder recorder = recorder(1, 60_000);
        RecordingToolCallback callback = new RecordingToolCallback(
                counting("analyzeResume", SUCCESS_PAYLOAD, executions), recorder);

        callback.call("{}");
        // 第 2 次调用：达到上限（maxToolCalls=1），拒绝执行
        String rejected = callback.call("{}");

        assertEquals(1, executions.get(), "达到上限后不应再执行工具");
        assertTrue(rejected.contains(AgentToolPayloads.ERROR_LIMIT_EXCEEDED), rejected);
        // AG-12：达到上限后被拒绝的调用不计入 toolCalls（记录数仍为 1）
        assertEquals(1, recorder.calls().size());
        assertTrue(recorder.truncated());
    }

    @Test
    void call_deadlineReached_skipsDelegateMarksTimeExhausted() {
        AtomicInteger executions = new AtomicInteger();
        // 预算 0 毫秒：deadlineReached 立即为真
        AgentToolCallRecorder recorder = recorder(5, 0);
        RecordingToolCallback callback = new RecordingToolCallback(
                counting("analyzeJob", SUCCESS_PAYLOAD, executions), recorder);

        String rejected = callback.call("{}");

        assertEquals(0, executions.get(), "超时后不应执行工具");
        assertTrue(rejected.contains(AgentToolPayloads.ERROR_TIME_EXHAUSTED), rejected);
        assertTrue(recorder.budget().isTimeExhausted());
        assertTrue(recorder.truncated());
        assertEquals(0, recorder.calls().size());
    }

    @Test
    void getToolDefinition_delegatesUnchanged() {
        ToolCallback delegate = stub("matchResumeToJob", SUCCESS_PAYLOAD);
        RecordingToolCallback callback = new RecordingToolCallback(
                delegate, recorder(5, 60_000));

        assertSame(delegate.getToolDefinition(), callback.getToolDefinition());
    }

    // ---------------------------------------------------------------- 工具

    private static AgentToolCallRecorder recorder(int maxToolCalls, long timeoutMillis) {
        return new AgentToolCallRecorder(new AgentToolBudget(timeoutMillis), maxToolCalls);
    }

    private static ToolCallback stub(String name, String result) {
        return counting(name, result, new AtomicInteger());
    }

    private static ToolCallback counting(String name, String result, AtomicInteger executions) {
        ToolDefinition definition = definition(name);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                executions.incrementAndGet();
                return result;
            }
        };
    }

    private static ToolCallback throwing(String name, RuntimeException ex) {
        ToolDefinition definition = definition(name);
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String toolInput) {
                throw ex;
            }
        };
    }

    private static ToolDefinition definition(String name) {
        return ToolDefinition.builder().name(name).description("测试用工具").inputSchema("{}").build();
    }

}
