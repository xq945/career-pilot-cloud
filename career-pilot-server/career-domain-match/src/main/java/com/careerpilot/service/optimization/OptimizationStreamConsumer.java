package com.careerpilot.service.optimization;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.careerpilot.common.async.AbstractStreamConsumer;
import com.careerpilot.common.constant.AsyncTaskStreamConstants;
import com.careerpilot.config.WorkerRoleCondition;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.infrastructure.redis.RedisStreamService;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.security.AuthenticatedUser;
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
@Conditional(WorkerRoleCondition.class)
public class OptimizationStreamConsumer extends AbstractStreamConsumer<OptimizationTask> {

    /**
     * 实例唯一标识：容器内 PID 恒为 1，只用 PID 会让所有副本共用同一个消费者名，
     * 导致 PEL 归属混乱、无法区分在途消息的持有者（阶段 1 · B3）。
     */
    private static final String INSTANCE_ID = UUID.randomUUID().toString().substring(0, 8);

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
        // 主机名 + 实例 UUID：跨主机/容器/重启均唯一，Redis PEL 才能正确归属
        return AsyncTaskStreamConstants.OPTIMIZATION_CONSUMER_PREFIX + hostName() + "-" + INSTANCE_ID;
    }

    private static String hostName() {
        try {
            return InetAddress.getLocalHost().getHostName();
        }
        catch (Exception ex) {
            return "local";
        }
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
        // 消费线程没有 HTTP 请求上下文。若不在执行前建立身份，AiCallTemplate 的
        // CurrentUser.idOrNull() 会返回 null，用户默认模型服务被静默跳过、回退平台默认，
        // 同步降级路径与异步路径的行为不一致（审计 B1）。
        AuthenticatedUser principal = new AuthenticatedUser(payload.userId(), "system");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        try {
            taskRunner.run(payload.optimizationId(), attemptId);
        } finally {
            SecurityContextHolder.clearContext();
        }
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
