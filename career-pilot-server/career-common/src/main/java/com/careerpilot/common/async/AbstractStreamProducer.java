package com.careerpilot.common.async;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.careerpilot.infrastructure.redis.RedisStreamService;

/**
 * Redis Stream 生产者模板（M16）：统一消息发送与失败处理骨架。
 *
 * <p>子类只需描述"发什么、失败时兜什么"，发送本身（含长度裁剪与异常包装）在此完成。
 * **入队失败必须可通过 {@link #onSendFailed} 兜底**（本项目的约定：降级为同步执行，
 * 而不是把任务静默丢失）。
 */
public abstract class AbstractStreamProducer<T> {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final RedisStreamService redisStreamService;

    protected AbstractStreamProducer(RedisStreamService redisStreamService) {
        this.redisStreamService = redisStreamService;
    }

    /** 发送任务；返回是否成功入队（失败时已回调 onSendFailed 兜底） */
    protected boolean sendTask(T payload) {
        try {
            String messageId = redisStreamService.streamAdd(streamKey(), buildMessage(payload), streamMaxLen());
            log.info("{}任务已入队：{} messageId={}", taskDisplayName(), payloadIdentifier(payload), messageId);
            return true;
        }
        catch (Exception ex) {
            log.error("{}任务入队失败：{} 异常={}", taskDisplayName(), payloadIdentifier(payload),
                    ex.getClass().getSimpleName());
            onSendFailed(payload, "任务入队失败：" + ex.getClass().getSimpleName());
            return false;
        }
    }

    protected abstract String taskDisplayName();

    protected abstract String streamKey();

    protected abstract Map<String, String> buildMessage(T payload);

    protected abstract String payloadIdentifier(T payload);

    /** Stream 近似长度上限（0 = 不限制） */
    protected abstract int streamMaxLen();

    /** 入队失败时的兜底（如：降级为同步执行 / 标记失败） */
    protected abstract void onSendFailed(T payload, String error);

}
