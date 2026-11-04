package com.careerpilot.service.optimization;

import java.time.LocalDateTime;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.dto.ai.ResumeOptimizationAiOutput;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.ResumeOptimizationMapper;

/**
 * 「创建优化」任务状态机的写路径（M16，《数据库设计》5.15）。
 *
 * <p>本类的每个方法都是**单条条件 UPDATE（CAS）**——不引入事务的必要性：
 * 单语句本身原子，且"状态 + 执行代次（attempt_id）"双条件是并发安全的核心：
 * <ul>
 *   <li>所有推进都要求当前状态符合预期（PENDING→PROCESSING 等），重复消费自然被拒；</li>
 *   <li>PROCESSING 之后的写入还要求 {@code attempt_id} 与本次代次一致——
 *       **进程崩溃任务被超时回收后，旧执行者再醒来也无法写入终态**（fencing）。</li>
 * </ul>
 *
 * <p>{@code updated_at} 的语义在此升级为「最后一次有效进展时间」：状态推进与阶段心跳
 * 都会显式推进它（恢复调度据此判定"卡住"）。
 */
@Service
@ConditionalOnProperty(prefix = "app.match", name = "mode", havingValue = "local", matchIfMissing = true)
public class OptimizationTaskStateService {

    private static final Logger log = LoggerFactory.getLogger(OptimizationTaskStateService.class);

    /** 校验明细入库上限（LONGTEXT 可容纳更多，但拒绝把异常级数据带入库） */
    private static final int FAILURE_DETAIL_LIMIT = 20000;

    private final ResumeOptimizationMapper optimizationMapper;

    public OptimizationTaskStateService(ResumeOptimizationMapper optimizationMapper) {
        this.optimizationMapper = optimizationMapper;
    }

    /**
     * 条件领取：PENDING → PROCESSING，并写入新的执行代次。
     * 返回新 attemptId；返回 null 表示领取失败（状态不符/已被其他执行者接管）——调用方应丢弃消息。
     */
    public String markProcessing(Long optimizationId) {
        String attemptId = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStatus(ResumeOptimization.STATUS_PROCESSING);
        patch.setAttemptId(attemptId);
        patch.setStage(ResumeOptimization.STAGE_MATCHING);
        patch.setStartedAt(now);
        patch.setErrorMessage(null);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PENDING)
                // 显式清空：MP 的实体更新会**跳过 null 字段**，清空类字段必须走 wrapper.set
                .set(ResumeOptimization::getErrorMessage, null)
                // 重新领取时清空上一次失败留下的校验明细（V1.13）
                .set(ResumeOptimization::getFailureDetail, null));
        if (updated == 1) {
            log.info("任务已领取：optimizationId={} attemptId={}", optimizationId, attemptId);
            return attemptId;
        }
        return null;
    }

    /**
     * 推进阶段（心跳）：仅当仍处于本次代次的 PROCESSING 时生效。
     *
     * @return false = 代次已失效（任务被恢复调度重置或被新执行者接管），调用方应立即停止，
     *         不要再发起后续模型调用（阶段 1 · B3）
     */
    public boolean updateStage(Long optimizationId, String attemptId, String stage) {
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStage(stage);
        patch.setUpdatedAt(LocalDateTime.now().withNano(0));
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                .eq(ResumeOptimization::getAttemptId, attemptId));
        if (updated != 1) {
            log.warn("阶段心跳被拒（代次已失效）：optimizationId={} attemptId={} stage={}",
                    optimizationId, attemptId, stage);
            return false;
        }
        return true;
    }

    /**
     * 成功落终态：写入全部产物并以「PROCESSING + attemptId」双条件提交。
     *
     * @return 是否由本次执行成功落库（false = 代次已失效，本次执行作废）
     */
    public boolean complete(Long optimizationId, String attemptId, Long matchRecordId,
                            ResumeOptimizationAiOutput generated, String modelName, String providerKey) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setMatchRecordId(matchRecordId);
        patch.setOptimizedContent(generated.getOptimizedContent());
        patch.setChanges(generated.getChanges());
        patch.setGapQuestions(generated.getGapQuestions());
        patch.setModelName(modelName);
        patch.setLlmProviderKey(providerKey);
        patch.setStatus(ResumeOptimization.STATUS_SUCCESS);
        patch.setErrorMessage(null);
        patch.setStage(null);
        patch.setFinishedAt(now);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                .eq(ResumeOptimization::getAttemptId, attemptId)
                // 显式清空 stage 与失败明细（MP 实体更新跳过 null，见 markProcessing 的说明）
                .set(ResumeOptimization::getStage, null)
                .set(ResumeOptimization::getFailureDetail, null));
        if (updated == 1) {
            log.info("任务已完成并落库：optimizationId={} matchRecordId={}", optimizationId, matchRecordId);
            return true;
        }
        log.warn("任务终态写入被拒（代次已失效，旧执行者作废）：optimizationId={} attemptId={}",
                optimizationId, attemptId);
        return false;
    }

    /**
     * 失败落终态（message 为用户可读文案）。
     * {@code attemptId} 为 null（领取阶段即失败）时**不写入**——让恢复调度按 PENDING 超时兜底。
     */
    public void fail(Long optimizationId, String attemptId, String message) {
        fail(optimizationId, attemptId, message, null);
    }

    /**
     * 失败落终态，附带事实校验的完整问题清单（V1.13）。
     *
     * @param failureDetail JSON 数组文本（已脱敏）；null 表示不是事实校验失败，不写入该列
     */
    public void fail(Long optimizationId, String attemptId, String message, String failureDetail) {
        if (attemptId == null) {
            log.warn("失败时无执行代次（领取阶段异常），交由恢复调度兜底：optimizationId={}", optimizationId);
            return;
        }
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStatus(ResumeOptimization.STATUS_FAILED);
        patch.setErrorMessage(truncate(message, 500));
        if (failureDetail != null && !failureDetail.isBlank()) {
            patch.setFailureDetail(truncate(failureDetail, FAILURE_DETAIL_LIMIT));
        }
        patch.setStage(null);
        patch.setFinishedAt(now);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                .eq(ResumeOptimization::getAttemptId, attemptId)
                // 显式清空 stage（MP 实体更新跳过 null）
                .set(ResumeOptimization::getStage, null));
        if (updated == 1) {
            log.info("任务已落失败态：optimizationId={} message={}", optimizationId, truncate(message, 120));
        }
    }

    // ---------------------------------------------------------------- 恢复调度专用

    /**
     * 补投前原子推进时间戳（仅 PENDING 且超过阈值）：多实例并发扫描时只有一个实例推进成功，
     * 从而只有一个实例补投（防重复投递）。
     */
    public boolean touchPendingForRecovery(Long optimizationId, LocalDateTime staleBefore) {
        ResumeOptimization patch = new ResumeOptimization();
        patch.setUpdatedAt(LocalDateTime.now().withNano(0));
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PENDING)
                .lt(ResumeOptimization::getUpdatedAt, staleBefore));
        return updated == 1;
    }

    /**
     * 重置超时 PROCESSING（消费者死亡）：清空代次回到 PENDING，等待补投。
     * 仅当自动恢复次数尚未达到上限时生效；达到上限的任务由
     * {@link #failExhaustedProcessing} 置为 FAILED。
     */
    public boolean resetStaleProcessing(Long optimizationId, LocalDateTime staleBefore, int maxRecoveryCount) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStatus(ResumeOptimization.STATUS_PENDING);
        patch.setAttemptId(null);
        patch.setStage(null);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                .lt(ResumeOptimization::getUpdatedAt, staleBefore)
                // 达到上限后不再重置，交由 failExhaustedProcessing 终结
                .lt(ResumeOptimization::getRecoveryCount, maxRecoveryCount)
                // 显式清空代次与阶段（MP 实体更新跳过 null）
                .set(ResumeOptimization::getAttemptId, null)
                .set(ResumeOptimization::getStage, null)
                // 自增必须在 SQL 中完成，避免"读-改-写"竞态
                .setSql("recovery_count = recovery_count + 1"));
        if (updated == 1) {
            log.warn("超时 PROCESSING 已重置为 PENDING（消费者疑似死亡）：optimizationId={}", optimizationId);
        }
        return updated == 1;
    }

    /**
     * 毒任务终结：PROCESSING 超时且自动恢复次数已达上限 → FAILED（不再无限重跑）。
     *
     * @return true = 本次已置失败（调用方不要再补投）
     */
    public boolean failExhaustedProcessing(Long optimizationId, LocalDateTime staleBefore,
                                           int maxRecoveryCount, String message) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStatus(ResumeOptimization.STATUS_FAILED);
        patch.setErrorMessage(truncate(message, 500));
        patch.setFinishedAt(now);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_PROCESSING)
                .lt(ResumeOptimization::getUpdatedAt, staleBefore)
                .ge(ResumeOptimization::getRecoveryCount, maxRecoveryCount)
                .set(ResumeOptimization::getAttemptId, null)
                .set(ResumeOptimization::getStage, null));
        if (updated == 1) {
            log.warn("任务自动恢复次数已达上限，置为 FAILED：optimizationId={} maxRecoveryCount={}",
                    optimizationId, maxRecoveryCount);
        }
        return updated == 1;
    }

    /** 用户手动重试：FAILED → PENDING（清失败信息与代次） */
    public boolean resetForRetry(Long optimizationId) {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ResumeOptimization patch = new ResumeOptimization();
        patch.setStatus(ResumeOptimization.STATUS_PENDING);
        patch.setAttemptId(null);
        patch.setStage(null);
        patch.setErrorMessage(null);
        patch.setStartedAt(null);
        patch.setFinishedAt(null);
        patch.setUpdatedAt(now);
        int updated = optimizationMapper.update(patch, Wrappers.<ResumeOptimization>lambdaUpdate()
                .eq(ResumeOptimization::getId, optimizationId)
                .eq(ResumeOptimization::getStatus, ResumeOptimization.STATUS_FAILED)
                // 显式清空失败痕迹与代次（MP 实体更新跳过 null）
                .set(ResumeOptimization::getAttemptId, null)
                .set(ResumeOptimization::getStage, null)
                .set(ResumeOptimization::getErrorMessage, null)
                .set(ResumeOptimization::getStartedAt, null)
                .set(ResumeOptimization::getFinishedAt, null)
                // 用户显式重试视为新的开始，重置自动恢复计数
                .set(ResumeOptimization::getRecoveryCount, 0));
        return updated == 1;
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        return text.length() > max ? text.substring(0, max) : text;
    }

}
