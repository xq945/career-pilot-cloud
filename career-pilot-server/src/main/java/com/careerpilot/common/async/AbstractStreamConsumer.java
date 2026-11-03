package com.careerpilot.common.async;

import java.util.Map;

import org.redisson.api.stream.StreamMessageId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import com.careerpilot.common.BusinessException;
import com.careerpilot.infrastructure.redis.RedisStreamService;

import jakarta.annotation.PreDestroy;

/**
 * Redis Stream 消费者模板（M16）：单线程消费循环 + 固定的消息编排。
 *
 * <p>每条消息的处理顺序（模板方法）：
 * {@code parsePayload → shouldSkip → tryMarkProcessing → processBusiness → markCompleted → ACK}，
 * 任何一步的语义：
 * <ul>
 *   <li><b>解析失败</b>：ACK 丢弃（消息坏了，重放没有意义）；</li>
 *   <li><b>shouldSkip（已终态）</b>：ACK 丢弃（重复投递的常态）；</li>
 *   <li><b>tryMarkProcessing 失败</b>：ACK 丢弃——条件领取（状态 + 代次 CAS）是**防重复消费的核心**，
 *       领取失败说明另一实例/上一轮已接管，重放只会重复烧模型调用；</li>
 *   <li><b>processBusiness 抛异常</b>：调 {@code markFailed} 落失败态后 ACK——
 *       <b>本项目不做自动重投</b>（模型失败多为确定性 502，自动重投会重复消耗 token），
 *       失败的任务由用户在详情页手动「重试」；进程崩溃（未 ACK）的场景由 Redis Pending
 *       回收 + 数据库恢复调度兜底。</li>
 * </ul>
 *
 * <p>消费线程是**长驻守护线程**（非虚拟线程池）：循环本身阻塞在 Redis 读上，
 * 业务执行在循环线程内串行（单实例单消费者，天然限流）。
 */
public abstract class AbstractStreamConsumer<T> implements DisposableBean {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final RedisStreamService redisStreamService;

    private Thread worker;

    private volatile boolean running;

    protected AbstractStreamConsumer(RedisStreamService redisStreamService) {
        this.redisStreamService = redisStreamService;
    }

    /** 应用就绪后再启动消费（避免启动期与上下文初始化竞争） */
    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (running) {
            return;
        }
        redisStreamService.createGroup(streamKey(), groupName());
        running = true;
        worker = new Thread(this::consumeLoop, consumerName());
        worker.setDaemon(true);
        worker.start();
        log.info("Stream 消费者已启动：stream={} group={} consumer={}", streamKey(), groupName(), consumerName());
    }

    @PreDestroy
    @Override
    public void destroy() {
        running = false;
        if (worker != null) {
            worker.interrupt();
        }
        log.info("Stream 消费者已停止：stream={} consumer={}", streamKey(), consumerName());
    }

    private void consumeLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                redisStreamService.consume(streamKey(), groupName(), consumerName(),
                        batchSize(), blockTimeoutMs(), pendingIdleTimeoutMs(), this::handleMessage);
            }
            catch (Exception ex) {
                // 循环级异常（Redis 抖动等）：记日志后继续，不退出循环
                log.warn("Stream 消费循环异常（将继续运行）：stream={} 异常={}",
                        streamKey(), ex.getClass().getSimpleName());
                sleepQuietly();
            }
        }
    }

    private void handleMessage(StreamMessageId messageId, Map<String, String> data) {
        T payload;
        try {
            payload = parsePayload(data);
        }
        catch (Exception ex) {
            log.warn("任务载荷解析失败，丢弃消息：stream={} messageId={}", streamKey(), messageId);
            ackQuietly(messageId);
            return;
        }

        String attemptId = null;
        try {
            if (shouldSkip(payload)) {
                log.info("任务已处于终态，跳过：{}", payloadIdentifier(payload));
                ackQuietly(messageId);
                return;
            }
            attemptId = tryMarkProcessing(payload);
            if (attemptId == null) {
                log.info("任务条件领取失败（已被处理或状态不符），丢弃消息：{}", payloadIdentifier(payload));
                ackQuietly(messageId);
                return;
            }
            processBusiness(payload, attemptId);
            markCompleted(payload, attemptId);
            ackQuietly(messageId);
            log.info("任务已完成：{}", payloadIdentifier(payload));
        }
        catch (Exception ex) {
            log.warn("任务执行失败：{} 异常={}", payloadIdentifier(payload), ex.getClass().getSimpleName());
            try {
                markFailed(payload, attemptId, userFacingMessage(ex), ex);
            }
            catch (Exception markEx) {
                log.warn("失败状态写入也失败（等待恢复调度兜底）：{} 异常={}",
                        payloadIdentifier(payload), markEx.getClass().getSimpleName());
            }
            // 失败同样 ACK：本项目不做自动重投（见类注释），由用户手动重试
            ackQuietly(messageId);
        }
    }

    private void ackQuietly(StreamMessageId messageId) {
        try {
            redisStreamService.ack(streamKey(), groupName(), messageId);
        }
        catch (Exception ex) {
            log.warn("ACK 失败（消息将滞留 Pending 被回收）：stream={} messageId={}", streamKey(), messageId);
        }
    }

    /** 把执行异常翻译为用户可读的失败文案（业务异常的文案本身即可读） */
    private static String userFacingMessage(Exception ex) {
        if (ex instanceof BusinessException businessException
                && businessException.getMessage() != null
                && !businessException.getMessage().isBlank()) {
            return truncate(businessException.getMessage(), 500);
        }
        return "任务执行失败（" + ex.getClass().getSimpleName() + "），可重试";
    }

    private static String truncate(String text, int max) {
        return text.length() > max ? text.substring(0, max) : text;
    }

    private static void sleepQuietly() {
        try {
            Thread.sleep(1000L);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------- 子类协议

    protected abstract String streamKey();

    protected abstract String groupName();

    protected abstract String consumerName();

    protected abstract T parsePayload(Map<String, String> data);

    /** 任务标识（日志用，如 optimizationId=123） */
    protected abstract String payloadIdentifier(T payload);

    /** 已处于终态（无需处理）时返回 true → ACK 丢弃 */
    protected abstract boolean shouldSkip(T payload);

    /** 条件领取：PENDING→PROCESSING 的 CAS 更新并生成执行代次；成功返回新 attemptId，失败返回 null（丢弃消息） */
    protected abstract String tryMarkProcessing(T payload);

    /** 业务执行（抛异常即失败；此时任务处于 PROCESSING 态、attemptId 为本次代次） */
    protected abstract void processBusiness(T payload, String attemptId);

    /** 成功落终态（实现须以「PROCESSING + attemptId」双条件写入；代次不符时 no-op） */
    protected abstract void markCompleted(T payload, String attemptId);

    /** 失败落终态（attemptId 可能为 null——领取阶段即抛异常时无代次可匹配，实现应跳过写入并交给恢复调度） */
    protected abstract void markFailed(T payload, String attemptId, String message);

    /**
     * 带原始异常的失败落终态（2026-10-21）：子类可据此持久化结构化的失败明细
     * （如定向优化的完整事实校验问题）。默认退化为三参版本，既有子类无需改动。
     */
    protected void markFailed(T payload, String attemptId, String message, Throwable cause) {
        markFailed(payload, attemptId, message);
    }

    protected int batchSize() {
        return 10;
    }

    protected long blockTimeoutMs() {
        return 2000L;
    }

    /** Pending 回收的 idle 阈值（毫秒）；0 表示不回收 */
    protected long pendingIdleTimeoutMs() {
        return 10 * 60 * 1000L;
    }

}
