package com.careerpilot.ai;

/**
 * 单次对话的时间预算（《API接口设计》§8.1：总时长不超过 120 秒）。
 *
 * <p>落地方式是**回合边界的协作式终止**，不是硬中断：整轮对话是一次阻塞调用，
 * 内部"模型回合 → 执行工具 → 再模型回合"的循环无法从外部打断一次在途的 HTTP 读。
 * 因此把截止时间做成"每个工具执行前检查一次"（见 {@link RecordingToolCallback}）：
 * 超时后不再执行任何工具、返回结构化超时结果，模型据此收尾作答。最坏墙钟为
 * 预算 + 一次在途模型调用（受 {@code spring.http.clients.read-timeout} 约束）。
 *
 * <p>每请求一个实例；工具在请求线程上串行执行（见 {@link AgentToolCallRecorder} 的并发说明）。
 */
public final class AgentToolBudget {

    private final long totalTimeoutMillis;

    private final long deadlineNanos;

    /** 是否因超时而拒绝过工具执行（truncated 判定的组成部分） */
    private volatile boolean timeExhausted;

    public AgentToolBudget(long totalTimeoutMillis) {
        this.totalTimeoutMillis = totalTimeoutMillis;
        this.deadlineNanos = System.nanoTime() + totalTimeoutMillis * 1_000_000L;
    }

    /** 预算总时长（秒级，用于日志与面向模型的提示文案） */
    public long totalTimeoutSeconds() {
        return totalTimeoutMillis / 1000L;
    }

    /** 是否已到达截止时间 */
    public boolean deadlineReached() {
        return System.nanoTime() >= deadlineNanos;
    }

    public boolean isTimeExhausted() {
        return timeExhausted;
    }

    public void markTimeExhausted() {
        this.timeExhausted = true;
    }

    /** 剩余预算毫秒数（用于日志与提示文案；已超时返回 0） */
    public long remainingMillis() {
        return Math.max(0L, (deadlineNanos - System.nanoTime()) / 1_000_000L);
    }

}
