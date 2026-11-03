package com.careerpilot.common.constant;

/**
 * 异步任务的 Redis Stream 常量（M16，《系统架构设计》§6.5）。
 *
 * <p>约定：Stream 键与消费组名集中在此，生产者/消费者/恢复调度三方共用，
 * 避免字符串散落（与参照项目的同名常量类对齐）。
 */
public final class AsyncTaskStreamConstants {

    private AsyncTaskStreamConstants() {
    }

    // ---------------------------------------------------------------- 「创建优化」任务流

    /** 任务流键 */
    public static final String OPTIMIZATION_STREAM_KEY = "careerpilot:stream:optimization-create";

    /** 消费组名 */
    public static final String OPTIMIZATION_GROUP = "optimization-workers";

    /** 消费者名前缀（单机部署下与主机名/时间戳拼接） */
    public static final String OPTIMIZATION_CONSUMER_PREFIX = "optimization-consumer-";

    /** Stream 近似长度上限（超出裁剪旧消息，防止无限增长） */
    public static final int STREAM_MAX_LEN = 1000;

    /** 单批读取条数 */
    public static final int BATCH_SIZE = 10;

    /** 阻塞读取超时（毫秒）：让 Redis 服务端等待，比客户端轮询省资源 */
    public static final long BLOCK_TIMEOUT_MS = 2000L;

    /** Pending 消息可被回收的 idle 阈值（毫秒）——必须大于单任务最长执行时间（10 分钟，与 processing-timeout 同口径） */
    public static final long PENDING_IDLE_TIMEOUT_MS = 10 * 60 * 1000L;

    // ---------------------------------------------------------------- 消息字段

    /** 优化记录 ID（任务载荷；其余信息以数据库行为事实来源） */
    public static final String FIELD_OPTIMIZATION_ID = "optimizationId";

    /** 发起用户 ID（执行期解析模型服务用） */
    public static final String FIELD_USER_ID = "userId";

}
