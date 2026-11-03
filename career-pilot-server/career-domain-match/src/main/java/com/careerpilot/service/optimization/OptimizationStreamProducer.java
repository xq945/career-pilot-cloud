package com.careerpilot.service.optimization;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.careerpilot.common.async.AbstractStreamProducer;
import com.careerpilot.common.constant.AsyncTaskStreamConstants;
import com.careerpilot.infrastructure.redis.RedisStreamService;

/**
 * 「创建优化」任务的生产者（M16）。
 *
 * <p>消息载荷只带 {@code optimizationId} 与 {@code userId}——任务内容以**数据库行为事实来源**
 * （消费者执行时重新读取实体），避免"消息与库状态不一致"的双写问题。
 *
 * <p>入队失败不在此兜底：{@link #send} 返回 false，由调用方（创建接口）**降级为同步执行**
 * ——不把任务静默丢失（《API接口设计》§6A.1 的兼容策略）。
 */
@Component
@ConditionalOnProperty(prefix = "app.optimization.async", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OptimizationStreamProducer extends AbstractStreamProducer<OptimizationStreamProducer.OptimizationTask> {

    private static final Logger log = LoggerFactory.getLogger(OptimizationStreamProducer.class);

    /** 任务载荷：优化记录 ID + 发起用户（执行期解析模型服务用） */
    public record OptimizationTask(Long optimizationId, Long userId) {
    }

    public OptimizationStreamProducer(RedisStreamService redisStreamService) {
        super(redisStreamService);
    }

    /** 发送任务；false = 入队失败（调用方应降级为同步执行） */
    public boolean send(OptimizationTask task) {
        return sendTask(task);
    }

    @Override
    protected String taskDisplayName() {
        return "创建优化";
    }

    @Override
    protected String streamKey() {
        return AsyncTaskStreamConstants.OPTIMIZATION_STREAM_KEY;
    }

    @Override
    protected Map<String, String> buildMessage(OptimizationTask payload) {
        return Map.of(
                AsyncTaskStreamConstants.FIELD_OPTIMIZATION_ID, payload.optimizationId().toString(),
                AsyncTaskStreamConstants.FIELD_USER_ID, payload.userId().toString());
    }

    @Override
    protected String payloadIdentifier(OptimizationTask payload) {
        return "optimizationId=" + payload.optimizationId();
    }

    @Override
    protected int streamMaxLen() {
        return AsyncTaskStreamConstants.STREAM_MAX_LEN;
    }

    @Override
    protected void onSendFailed(OptimizationTask payload, String error) {
        log.warn("创建优化任务入队失败，调用方将降级为同步执行：{}（{}）", payloadIdentifier(payload), error);
    }

}
