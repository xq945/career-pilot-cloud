package com.careerpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.careerpilot.common.BusinessException;
import com.careerpilot.common.PageQuery;
import com.careerpilot.dto.InterviewScheduleSaveRequest;
import com.careerpilot.entity.InterviewSchedule;
import com.careerpilot.mapper.InterviewScheduleMapper;
import com.careerpilot.service.schedule.InterviewInvitationParser;
import com.careerpilot.service.schedule.ScheduleParseAiFallback;

/**
 * 面试日程的业务规则（M17，《数据库设计》5.16）：时间必须未来、仅 PENDING 可编辑 / 流转、
 * 终态不可回退、删除为物理删除、越权一律 404。
 */
class InterviewScheduleServiceTest {

    private InterviewScheduleMapper scheduleMapper;

    private InterviewScheduleService service;

    @BeforeEach
    void setUp() {
        scheduleMapper = mock(InterviewScheduleMapper.class);
        service = new InterviewScheduleService(scheduleMapper,
                mock(InterviewInvitationParser.class), mock(ScheduleParseAiFallback.class));
    }

    @Test
    @DisplayName("创建：过去时间 → 400，不落库")
    void createRejectsPastTime() {
        InterviewScheduleSaveRequest request = request(LocalDateTime.now().minusHours(1));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(1L, request));

        assertEquals(40000, ex.getCode());
        assertTrue(ex.getMessage().contains("未来"));
        verify(scheduleMapper, never()).insert(any(InterviewSchedule.class));
    }

    @Test
    @DisplayName("创建：缺省来源归一为 MANUAL，状态为 PENDING")
    void createDefaults() {
        InterviewScheduleSaveRequest request = request(LocalDateTime.now().plusDays(2));

        service.create(1L, request);

        var captor = org.mockito.ArgumentCaptor.forClass(InterviewSchedule.class);
        verify(scheduleMapper).insert(captor.capture());
        InterviewSchedule saved = captor.getValue();
        assertEquals(InterviewSchedule.STATUS_PENDING, saved.getStatus());
        assertEquals(InterviewSchedule.SOURCE_MANUAL, saved.getParseSource());
        assertEquals(InterviewSchedule.TYPE_VIDEO, saved.getInterviewType());
        assertEquals(1, saved.getRoundNumber());
    }

    @Test
    @DisplayName("状态流转：终态（已完成）不可再流转 → 400，不写入")
    void statusOnlyForPending() {
        when(scheduleMapper.selectOne(any())).thenReturn(stored(InterviewSchedule.STATUS_COMPLETED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateStatus(1L, 9L, "CANCELLED"));

        assertTrue(ex.getMessage().contains("已结束"));
        verify(scheduleMapper, never()).updateById(any(InterviewSchedule.class));
    }

    @Test
    @DisplayName("状态流转：PENDING → COMPLETED 成功")
    void statusTransitionFromPending() {
        InterviewSchedule record = stored(InterviewSchedule.STATUS_PENDING);
        when(scheduleMapper.selectOne(any())).thenReturn(record);

        service.updateStatus(1L, 9L, "COMPLETED");

        assertEquals(InterviewSchedule.STATUS_COMPLETED, record.getStatus());
        verify(scheduleMapper).updateById(record);
    }

    @Test
    @DisplayName("编辑：仅 PENDING 可编辑（已过期 → 400）")
    void updateOnlyForPending() {
        when(scheduleMapper.selectOne(any())).thenReturn(stored(InterviewSchedule.STATUS_EXPIRED));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.update(1L, 9L, request(LocalDateTime.now().plusDays(1))));

        assertTrue(ex.getMessage().contains("不可修改"));
    }

    @Test
    @DisplayName("删除：命中归属则物理删除")
    void deletePhysical() {
        when(scheduleMapper.selectOne(any())).thenReturn(stored(InterviewSchedule.STATUS_PENDING));

        service.delete(1L, 9L);

        verify(scheduleMapper).deleteById(9L);
    }

    @Test
    @DisplayName("删除：他人记录（查不到）→ 404，不删除")
    void deleteOtherUsersRecord() {
        when(scheduleMapper.selectOne(any())).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.delete(1L, 9L));
        verify(scheduleMapper, never()).deleteById(anyLong());
    }

    @Test
    @DisplayName("列表：非法状态筛选值 → 400")
    void listRejectsUnknownStatus() {
        assertThrows(BusinessException.class,
                () -> service.list(1L, "UNKNOWN", null, null, new PageQuery()));
    }

    private static InterviewScheduleSaveRequest request(LocalDateTime interviewTime) {
        InterviewScheduleSaveRequest request = new InterviewScheduleSaveRequest();
        request.setCompanyName("示例科技");
        request.setPosition("Java 后端");
        request.setInterviewTime(interviewTime);
        return request;
    }

    private static InterviewSchedule stored(String status) {
        InterviewSchedule record = new InterviewSchedule();
        record.setId(9L);
        record.setUserId(1L);
        record.setCompanyName("示例科技");
        record.setPosition("Java 后端");
        record.setStatus(status);
        return record;
    }

}
