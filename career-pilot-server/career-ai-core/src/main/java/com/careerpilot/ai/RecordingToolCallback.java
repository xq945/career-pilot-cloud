package com.careerpilot.ai;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import com.careerpilot.common.BusinessException;

/**
 * 记录型工具回调：包装原始 {@code ToolCallback}，在执行前后记录调用信息并执行两类拦截
 * （《Agent设计》§7.5 的首选方案：与具体版本耦合最低；{@code ToolCallback} 为公开接口）。
 *
 * <p>职责：
 * <ol>
 *   <li><b>次数上限软拦截</b>：达到 {@code app.agent.max-tool-calls} 后，到来的调用不执行、
 *       返回中文失败信封（模型据此收尾作答，实测收尾质量良好）；</li>
 *   <li><b>时间预算拦截</b>：超过总时长预算后同样拒绝执行（协作式终止，见 {@link AgentToolBudget}）；</li>
 *   <li><b>记录</b>：执行结果（成功/失败、耗时、summary）写入 {@link AgentToolCallRecorder}；</li>
 *   <li><b>异常转信封</b>：工具抛出的 {@link RuntimeException}（归属 404、下游 502 等）
 *       不向上冒泡，转为失败信封交回模型（AG-10：说明失败、不编造）。
 *       —— 与"整轮 502 不落库"严格区分：那是**外层对话调用**失败；工具内部失败时
 *       外层调用是成功的，整轮照常落库。</li>
 * </ol>
 *
 * <p>{@link #getToolDefinition()} 原样委托：模型看到的 JSON Schema 与工具方法逐字一致
 * （参数仅资源 ID，**绝无 userId**；《Agent设计》§4.1）。
 */
public final class RecordingToolCallback implements ToolCallback {

    private static final Logger log = LoggerFactory.getLogger(RecordingToolCallback.class);

    private final ToolCallback delegate;

    private final AgentToolCallRecorder recorder;

    public RecordingToolCallback(ToolCallback delegate, AgentToolCallRecorder recorder) {
        this.delegate = delegate;
        this.recorder = recorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public String call(String toolInput) {
        return call(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();

        if (recorder.isLimitReached()) {
            recorder.markLimitRejected();
            log.warn("Agent 工具次数上限拦截：tool={} 已执行={} 上限={}",
                    toolName, recorder.calls().size(), recorder.maxToolCalls());
            return AgentToolPayloads.failure(AgentToolPayloads.ERROR_LIMIT_EXCEEDED,
                    "本次对话已达到工具调用次数上限（" + recorder.maxToolCalls()
                            + " 次），该工具未执行。请基于已经取得的结果作答，并说明未能完成的部分。");
        }

        if (recorder.budget().deadlineReached()) {
            recorder.budget().markTimeExhausted();
            log.warn("Agent 对话时长上限拦截：tool={} 剩余预算=0ms", toolName);
            return AgentToolPayloads.failure(AgentToolPayloads.ERROR_TIME_EXHAUSTED,
                    "本次对话已超过总时长上限（" + recorder.budget().totalTimeoutSeconds()
                            + " 秒），该工具未执行。请基于已经取得的结果作答，并说明未能完成的部分。");
        }

        long startNanos = System.nanoTime();
        LocalDateTime startedAt = LocalDateTime.now().withNano(0);
        try {
            String result = toolContext == null
                    ? delegate.call(toolInput)
                    : delegate.call(toolInput, toolContext);
            long elapsed = elapsedMillis(startNanos);
            // 工具方法也可能返回失败信封（业务上的"算不了"但非异常），status 以信封为准
            String status = AgentToolPayloads.statusOf(result);
            recorder.record(new RecordedToolCall(toolName, toolInput, status,
                    AgentToolPayloads.summaryOf(result), elapsed, startedAt));
            log.info("Agent 工具调用完成：tool={} status={} 耗时={}ms", toolName, status, elapsed);
            return result;
        }
        catch (RuntimeException ex) {
            long elapsed = elapsedMillis(startNanos);
            String summary = failureSummary(ex);
            recorder.record(new RecordedToolCall(toolName, toolInput, RecordedToolCall.STATUS_FAILED,
                    summary, elapsed, startedAt));
            if (isAuthenticationLoss(ex)) {
                // 工具线程拿不到认证上下文属系统缺陷，单独标红（PoC 实测正常路径可读到）
                log.error("Agent 工具丢失认证上下文：tool={}", toolName, ex);
            }
            else {
                log.warn("Agent 工具调用失败：tool={} 耗时={}ms 异常={}",
                        toolName, elapsed, ex.getClass().getSimpleName());
            }
            return AgentToolPayloads.failure(AgentToolPayloads.ERROR_TOOL_FAILED, summary);
        }
    }

    /** 异常 → 面向模型的失败说明（BusinessException 的业务文案可安全外露） */
    private static String failureSummary(RuntimeException ex) {
        if (ex instanceof BusinessException business) {
            String message = business.getMessage();
            if (message != null && !message.isBlank()) {
                return "工具执行失败：" + message;
            }
        }
        return "工具执行失败：服务暂时不可用";
    }

    private static boolean isAuthenticationLoss(RuntimeException ex) {
        return ex instanceof BusinessException business && business.getCode() == 40100;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

}
