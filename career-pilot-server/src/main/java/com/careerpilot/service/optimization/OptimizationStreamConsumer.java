package com.careerpilot.service.optimization;

import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.careerpilot.common.async.AbstractStreamConsumer;
import com.careerpilot.common.constant.AsyncTaskStreamConstants;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.infrastructure.redis.RedisStreamService;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.optimization.OptimizationStreamProducer.OptimizationTask;

/**
 * 「创建优化」任务的消费者（M16）：单线程消费循环（模板在 {@link AbstractStreamConsumer}）。
 *
 * <p>编排语义见基类注释；本类的三个决策点：
 * <ul>
 *   <li>{@link #shouldSkip}：记录不存在或已终态（SUCCESS/FAILED）→ ACK 丢弃（重复投递常态）；</li>
 *   <li>{@link #tryMarkProcessing}：条件领取（PENDING→PROCESSING + 新一代次），失败即丢弃；</li>
 *   <li>{@link #markCompleted} / {@link #markFailed}：全部经状态机的"状态 + 代次"双条件写入。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.optimization.async", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OptimizationStreamConsumer extends AbstractStreamConsumer<OptimizationTask> {

    private final ResumeOptimizationMapper optimizationMapper;

    private final OptimizationTaskStateService stateService;

    private final OptimizationTaskRunner taskRunner;

    public OptimizationStreamConsumer(RedisStreamService redisStreamService,
                                      ResumeOptimizationMapper optimizationMapper,
                                      OptimizationTaskStateService stateService,
                                      OptimizationTaskRunner taskRunner) {
        super(redisStreamService);
        this.optimizationMapper = optimizationMapper;
        this.stateService = stateService;
        this.taskRunner = taskRunner;
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.OPTIMIZATION_STREAM_KEY;
    }

    @Override
    protected String groupName() {
        return AsyncTaskStreamConstants.OPTIMIZATION_GROUP;
    }

    @Override
    protected String consumerName() {
        // 单机部署：固定前缀 + JVM 进程号（多实例/多进程时可区分）
        return AsyncTaskStreamConstants.OPTIMIZATION_CONSUMER_PREFIX + ProcessHandle.current().pid();
    }

    @Override
    protected OptimizationTask parsePayload(Map<String, String> data) {
        Long optimizationId = Long.valueOf(data.get(AsyncTaskStreamConstants.FIELD_OPTIMIZATION_ID));
        Long userId = Long.valueOf(data.get(AsyncTaskStreamConstants.FIELD_USER_ID));
        return new OptimizationTask(optimizationId, userId);
    }

    @Override
    protected String payloadIdentifier(OptimizationTask payload) {
        return "optimizationId=" + payload.optimizationId();
    }

    @Override
    protected boolean shouldSkip(OptimizationTask payload) {
        ResumeOptimization record = optimizationMapper.selectById(payload.optimizationId());
        if (record == null) {
            return true;
        }
        return ResumeOptimization.STATUS_SUCCESS.equals(record.getStatus())
                || ResumeOptimization.STATUS_FAILED.equals(record.getStatus());
    }

    @Override
    protected String tryMarkProcessing(OptimizationTask payload) {
        return stateService.markProcessing(payload.optimizationId());
    }

    @Override
    protected void processBusiness(OptimizationTask payload, String attemptId) {
        taskRunner.run(payload.optimizationId(), attemptId);
    }

    @Override
    protected void markCompleted(OptimizationTask payload, String attemptId) {
        // 产物落库由 OptimizationTaskRunner 内的 stateService.complete 完成（含代次校验）——
        // 此处无需重复写入；保留钩子以便未来追加"完成后的通知"类副作用。
    }

    @Override
    protected void markFailed(OptimizationTask payload, String attemptId, String message) {
        stateService.fail(payload.optimizationId(), attemptId, message);
    }

    /**
     * 事实校验失败时一并保存完整问题清单（V1.13）；其他异常沿用基类文案。
     */
    @Override
    protected void markFailed(OptimizationTask payload, String attemptId, String message,
                              Throwable cause) {
        String failureDetail = cause instanceof OptimizationFactCheckException factCheck
                ? factCheck.failureDetailJson()
                : null;
        stateService.fail(payload.optimizationId(), attemptId, message, failureDetail);
    }

    @Override
    protected int batchSize() {
        return AsyncTaskStreamConstants.BATCH_SIZE;
    }

    @Override
    protected long blockTimeoutMs() {
        return AsyncTaskStreamConstants.BLOCK_TIMEOUT_MS;
    }

    @Override
    protected long pendingIdleTimeoutMs() {
        return AsyncTaskStreamConstants.PENDING_IDLE_TIMEOUT_MS;
    }

}
