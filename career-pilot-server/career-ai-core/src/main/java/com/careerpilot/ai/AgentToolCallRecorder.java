package com.careerpilot.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 单次请求的工具调用记录器（《Agent设计》§7.5：包装 {@code ToolCallback} 的最低耦合方案）。
 *
 * <p><b>每请求一个实例</b>，随请求构造、随请求丢弃——不放进单例、不用 ThreadLocal：
 * 工具回调列表本就是每个请求现场构建的（见 {@link RecordingToolCallback} 的包装）。
 *
 * <p>并发说明：整轮对话是阻塞调用，同一 assistant 消息里的多个工具调用由框架**顺序**执行
 * （请求线程上串行），因此 {@code ArrayList} 足够；字段**仅供请求线程访问**。
 *
 * <p>次数上限（{@code app.agent.max-tool-calls}，默认 5；《Agent设计》§8）在本类配合
 * {@link RecordingToolCallback} 执行"软拦截"：达到上限后继续到来的调用不执行、返回中文信封。
 * yml 中另配了宽松的框架兜底（{@code spring.ai.tools.limits}，20 次 + RETURN_ERROR_RESPONSE）
 * 防失控——框架侧行为经 PoC 实测：THROW 不抛异常而是给出英文固定文案，不能作为产品文案来源。
 */
public final class AgentToolCallRecorder {

    private final AgentToolBudget budget;

    private final int maxToolCalls;

    /** 已实际执行（含失败）的工具调用；达到上限后被拒绝的调用**不**计入（AG-12：toolCalls ≤ 5） */
    private final List<RecordedToolCall> calls = new ArrayList<>();

    /** 是否发生过"达到次数上限"的拒绝（truncated 判定的组成部分） */
    private boolean limitRejected;

    public AgentToolCallRecorder(AgentToolBudget budget, int maxToolCalls) {
        this.budget = budget;
        this.maxToolCalls = maxToolCalls;
    }

    public AgentToolBudget budget() {
        return budget;
    }

    public int maxToolCalls() {
        return maxToolCalls;
    }

    /** 是否已达次数上限（第 maxToolCalls 次执行之后为 true） */
    public boolean isLimitReached() {
        return calls.size() >= maxToolCalls;
    }

    public void markLimitRejected() {
        this.limitRejected = true;
    }

    public void record(RecordedToolCall call) {
        calls.add(call);
    }

    /** 已执行的调用（快照） */
    public List<RecordedToolCall> calls() {
        return List.copyOf(calls);
    }

    /**
     * 本轮是否被截断（《API接口设计》§8.1 / R05：truncated 属成功路径、照常落库，
     * 且仅为响应字段——不落库、历史消息中不做标记）。
     */
    public boolean truncated() {
        return limitRejected || budget.isTimeExhausted();
    }

}
