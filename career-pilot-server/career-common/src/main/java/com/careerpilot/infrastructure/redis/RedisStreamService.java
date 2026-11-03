package com.careerpilot.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.AutoClaimResult;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamCreateGroupArgs;
import org.redisson.api.stream.StreamMessageId;
import org.redisson.api.stream.StreamReadGroupArgs;
import org.redisson.client.codec.StringCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Redis Stream 的收发封装（M16）：发送、建组、阻塞消费（含 Pending 回收）、ACK。
 *
 * <p>消费顺序为「先回收超时 Pending、再阻塞读新消息」：消费者崩溃时未 ACK 的消息
 * 由后续实例（或重启后的本实例）回收重放，是最小代价的可靠性兜底
 * （数据库侧的状态机与恢复调度再兜一层）。
 *
 * <p><b>Redisson 4.0.0 兼容说明</b>：{@code readGroup} / {@code autoClaim} 在**空结果**时
 * 可能抛 {@code ClassCastException}（内部以空列表代替空 Map 的已知缺陷）——
 * 两处均已捕获并等价为"本轮无消息"（做法与参照项目一致，升级 Redisson 后可清理）。
 */
@Service
@ConditionalOnProperty(prefix = "app.optimization.async", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class RedisStreamService {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamService.class);

    private final RedissonClient redissonClient;

    /** Pending 回收游标（按 stream+group 维度记录下次从哪个消息 ID 继续扫描） */
    private final ConcurrentMap<String, StreamMessageId> reclaimCursors = new ConcurrentHashMap<>();

    public RedisStreamService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /** 发送消息（带长度裁剪）；返回消息 ID */
    public String streamAdd(String streamKey, Map<String, String> message, int maxLen) {
        RStream<String, String> stream = stream(streamKey);
        StreamAddArgs<String, String> args = StreamAddArgs.entries(message);
        if (maxLen > 0) {
            args.trimNonStrict().maxLen(maxLen);
        }
        StreamMessageId id = stream.add(args);
        log.debug("Stream 消息已发送：stream={} messageId={}", streamKey, id);
        return id.toString();
    }

    /** 创建消费组（组已存在时静默忽略） */
    public void createGroup(String streamKey, String groupName) {
        RStream<String, String> stream = stream(streamKey);
        try {
            stream.createGroup(StreamCreateGroupArgs.name(groupName).makeStream());
            log.info("Stream 消费组已创建：stream={} group={}", streamKey, groupName);
        }
        catch (Exception ex) {
            // BUSYGROUP：组已存在（多实例/重启的常态），忽略
            if (ex.getMessage() != null && ex.getMessage().contains("BUSYGROUP")) {
                return;
            }
            log.warn("创建 Stream 消费组失败：stream={} group={} 异常={}",
                    streamKey, groupName, ex.getClass().getSimpleName());
        }
    }

    /** ACK 消息（处理完成或明确丢弃时调用；不 ACK 的消息会滞留 Pending 被反复回收） */
    public void ack(String streamKey, String groupName, StreamMessageId... ids) {
        stream(streamKey).ack(groupName, ids);
    }

    /**
     * 消费一轮：先回收超时 Pending（游标分页），再阻塞读新消息。
     *
     * @param pendingIdleTimeoutMs Pending 超过该 idle 时间后可被当前消费者回收（&lt;=0 表示不回收）
     * @return 本轮是否有消息被处理
     */
    public boolean consume(String streamKey, String groupName, String consumerName,
                           int count, long blockTimeoutMs, long pendingIdleTimeoutMs,
                           StreamMessageHandler handler) {
        RStream<String, String> stream = stream(streamKey);

        Map<StreamMessageId, Map<String, String>> reclaimed = reclaimPending(
                stream, streamKey, groupName, consumerName, count, pendingIdleTimeoutMs);
        if (process(reclaimed, handler)) {
            return true;
        }

        Map<StreamMessageId, Map<String, String>> messages;
        try {
            messages = stream.readGroup(groupName, consumerName,
                    StreamReadGroupArgs.neverDelivered()
                            .count(count)
                            .timeout(Duration.ofMillis(blockTimeoutMs)));
        }
        catch (ClassCastException ex) {
            // Redisson 4.0.0 空结果缺陷（等价于"本轮无新消息"）
            log.debug("Redisson 空结果类型转换异常（等价本轮无消息）：stream={} group={}", streamKey, groupName);
            return false;
        }
        return process(messages, handler);
    }

    private Map<StreamMessageId, Map<String, String>> reclaimPending(
            RStream<String, String> stream, String streamKey, String groupName, String consumerName,
            int count, long pendingIdleTimeoutMs) {
        if (pendingIdleTimeoutMs <= 0 || count <= 0) {
            return Map.of();
        }
        String cursorKey = streamKey + ":" + groupName;
        StreamMessageId startId = reclaimCursors.getOrDefault(cursorKey, StreamMessageId.MIN);
        AutoClaimResult<String, String> result;
        try {
            result = stream.autoClaim(groupName, consumerName, pendingIdleTimeoutMs,
                    TimeUnit.MILLISECONDS, startId, count);
        }
        catch (ClassCastException ex) {
            log.debug("Redisson 空结果类型转换异常（等价本轮无可回收 Pending）：stream={} group={}",
                    streamKey, groupName);
            return Map.of();
        }

        StreamMessageId nextId = result.getNextId();
        if (nextId == null || StreamMessageId.MIN.equals(nextId)) {
            reclaimCursors.remove(cursorKey);
        }
        else {
            reclaimCursors.put(cursorKey, nextId);
        }

        List<StreamMessageId> deletedIds = result.getDeletedIds();
        if (deletedIds != null && !deletedIds.isEmpty()) {
            // Stream 裁剪导致 Pending 消息体丢失：只能记日志（数据库侧的恢复调度会兜底补投）
            log.warn("Stream Pending 消息已被裁剪，无法回收：stream={} group={} ids={}",
                    streamKey, groupName, deletedIds);
        }

        Map<StreamMessageId, Map<String, String>> messages = result.getMessages();
        if (messages != null && !messages.isEmpty()) {
            log.info("已回收 Stream Pending 消息：stream={} group={} consumer={} count={}",
                    streamKey, groupName, consumerName, messages.size());
        }
        return messages == null ? Map.of() : messages;
    }

    private boolean process(Map<StreamMessageId, Map<String, String>> messages, StreamMessageHandler handler) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        for (Map.Entry<StreamMessageId, Map<String, String>> entry : messages.entrySet()) {
            handler.handle(entry.getKey(), entry.getValue());
        }
        return true;
    }

    private RStream<String, String> stream(String streamKey) {
        return redissonClient.getStream(streamKey, StringCodec.INSTANCE);
    }

    /** 单条消息的处理器（messageId 用于 ACK，data 为字段表） */
    @FunctionalInterface
    public interface StreamMessageHandler {
        void handle(StreamMessageId messageId, Map<String, String> data);
    }

}
