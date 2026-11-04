package com.careerpilot.service.optimization;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.config.OptimizationAsyncProperties;
import com.careerpilot.config.WorkerRoleCondition;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.optimization.OptimizationStreamProducer.OptimizationTask;

/**
 * 「创建优化」卡住任务的恢复调度（M16）：定时扫描数据库中的任务行，把**没人再管**的任务补投。
 *
 * <p>两类"卡住"（阈值见 {@link OptimizationAsyncProperties}）：
 * <ul>
 *   <li><b>PENDING 超时</b>（投递丢失）：Redis 抖动/入队异常导致消息没进 Stream，
 *       行永远停 PENDING——先原子推进时间戳（多实例只有一个成功）再补投；</li>
 *   <li><b>PROCESSING 超时</b>（消费者死亡）：进程崩溃导致任务无人推进——
 *       重置为 PENDING（清代次，旧执行者的后续写入会被 fencing 拒绝）后补投。</li>
 * </ul>
 *
 * <p>补投仍走同一条 Stream（消费者侧的"条件领取"保证重复消息不会重复执行）。
 * PROCESSING 自动恢复有次数上限（{@code max-recovery-count}）：达到上限的毒任务直接置
 * FAILED，避免无限重跑；用户手动重试会重置计数（见 §6A.7）。
 */
@Component
@ConditionalOnProperty(prefix = "app.optimization.async", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@Conditional(WorkerRoleCondition.class)
public class OptimizationRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OptimizationRecoveryScheduler.class);

    /** 单轮扫描条数上限（防止一次补投过多压垮消费者） */
    private static final int SCAN_BATCH_SIZE = 10;

    private final ResumeOptimizationMapper optimizationMapper;

    private final OptimizationTaskStateService stateService;

    private final OptimizationStreamProducer streamProducer;

    private final OptimizationAsyncProperties properties;

    public OptimizationRecoveryScheduler(ResumeOptimizationMapper optimizationMapper,
                                          OptimizationTaskStateService stateService,
                                          OptimizationStreamProducer streamProducer,
                                          OptimizationAsyncProperties properties) {
        this.optimizationMapper = optimizationMapper;
        this.stateService = stateService;
        this.streamProducer = streamProducer;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${app.optimization.async.scan-interval:60s}")
    public void recoverStuckTasks() {
        // 以数据库时钟为唯一基准：多实例跨时区/时钟漂移时，判定不能被各 JVM 的本地时间左右
        // （阶段 1 · B3）。拿不到时回退本地时间，保证单测/无 DB 场景仍可运行。
        LocalDateTime now = optimizationMapper.currentTimestamp();
        if (now == null) {
            now = LocalDateTime.now();
        }
        now = now.withNano(0);
        int republished = recoverPending(now) + recoverProcessing(now);
        if (republished > 0) {
            log.info("恢复调度补投完成：本轮 {} 条", republished);
        }
    }

    /** PENDING 超时（投递丢失）：原子推进时间戳后补投 */
    private int recoverPending(LocalDateTime now) {
        LocalDateTime staleBefore = now.minus(properties.pendingTimeout());
        List<ResumeOptimization> stale = optimizationMapper.selectList(
                Wrappers.<ResumeOptimization>lambdaQuery()
                        .select(ResumeOptimization::getId, ResumeOptimization::getUserId)
                        .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PENDING)
                        .lt(ResumeOptimization::getUpdatedAt, staleBefore)
                        .orderByAsc(ResumeOptimization::getUpdatedAt)
                        .last("LIMIT " + SCAN_BATCH_SIZE));
        int republished = 0;
        for (ResumeOptimization record : stale) {
            if (stateService.touchPendingForRecovery(record.getId(), staleBefore)) {
                if (streamProducer.send(new OptimizationTask(record.getId(), record.getUserId()))) {
                    republished++;
                }
                else {
                    log.warn("恢复补投入队失败（下一轮重试）：optimizationId={}", record.getId());
                }
            }
        }
        return republished;
    }

    /** PROCESSING 超时（消费者死亡）：达到恢复上限则终结，否则重置回 PENDING 后补投 */
    private int recoverProcessing(LocalDateTime now) {
        LocalDateTime staleBefore = now.minus(properties.processingTimeout());
        int maxRecoveryCount = properties.maxRecoveryCount();
        List<ResumeOptimization> stale = optimizationMapper.selectList(
                Wrappers.<ResumeOptimization>lambdaQuery()
                        .select(ResumeOptimization::getId, ResumeOptimization::getUserId)
                        .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                        .lt(ResumeOptimization::getUpdatedAt, staleBefore)
                        .orderByAsc(ResumeOptimization::getUpdatedAt)
                        .last("LIMIT " + SCAN_BATCH_SIZE));
        int republished = 0;
        for (ResumeOptimization record : stale) {
            // 毒任务：自动恢复次数已达上限，置 FAILED 后不再补投
            if (stateService.failExhaustedProcessing(record.getId(), staleBefore, maxRecoveryCount,
                    "任务多次自动恢复失败，已停止重试，请重新创建优化")) {
                continue;
            }
            if (stateService.resetStaleProcessing(record.getId(), staleBefore, maxRecoveryCount)) {
                if (streamProducer.send(new OptimizationTask(record.getId(), record.getUserId()))) {
                    republished++;
                }
                else {
                    log.warn("恢复补投入队失败（下一轮重试）：optimizationId={}", record.getId());
                }
            }
        }
        return republished;
    }

}
