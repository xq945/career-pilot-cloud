package com.careerpilot.service.schedule;

import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.careerpilot.entity.InterviewSchedule;
import com.careerpilot.mapper.InterviewScheduleMapper;

/**
 * 面试日程的**过期流转**（M17，《数据库设计》5.16）：定时把"面试时间已过仍待处理"的日程
 * 置为 `EXPIRED`（**仅系统写入**的状态）。
 *
 * <p>实现为**单条批量条件更新**（不逐行加载）：只命中原状态为 `PENDING` 且时间已过的行——
 * 已完成 / 已取消的记录不受影响（终态不可回退是全局约定）。
 *
 * <p>cron 由 `app.schedule.expiry-cron` 配置（默认每小时整点）；验证脚本可用
 * `--app.schedule.expiry-cron="0/10 * * * * *"` 加速（每 10 秒；注意 JavaDoc 中不可
 * 直接写星号斜杠的 cron 简写——会提前终止块注释）。
 */
@Component
public class ScheduleStatusUpdater {

    private static final Logger log = LoggerFactory.getLogger(ScheduleStatusUpdater.class);

    private final InterviewScheduleMapper scheduleMapper;

    public ScheduleStatusUpdater(InterviewScheduleMapper scheduleMapper) {
        this.scheduleMapper = scheduleMapper;
    }

    @Scheduled(cron = "${app.schedule.expiry-cron:0 0 * * * *}")
    public void expireOutdatedSchedules() {
        LocalDateTime now = LocalDateTime.now().withNano(0);
        int updated = scheduleMapper.update(null, Wrappers.<InterviewSchedule>lambdaUpdate()
                .set(InterviewSchedule::getStatus, InterviewSchedule.STATUS_EXPIRED)
                .set(InterviewSchedule::getUpdatedAt, now)
                .eq(InterviewSchedule::getStatus, InterviewSchedule.STATUS_PENDING)
                .lt(InterviewSchedule::getInterviewTime, now));
        if (updated > 0) {
            log.info("面试日程过期流转：{} 条 PENDING → EXPIRED", updated);
        }
    }

}
