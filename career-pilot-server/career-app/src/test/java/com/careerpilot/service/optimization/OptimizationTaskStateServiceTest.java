package com.careerpilot.service.optimization;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.mapper.ResumeOptimizationMapper;

/**
 * 任务状态机的 CAS 返回值语义（阶段 1 · B3）：
 * 心跳/恢复的 0 行结果必须能被调用方感知，否则旧代次会继续消耗模型调用。
 */
class OptimizationTaskStateServiceTest {

    private final ResumeOptimizationMapper mapper = mock(ResumeOptimizationMapper.class);

    private final OptimizationTaskStateService state = new OptimizationTaskStateService(mapper);

    @Test
    void markProcessing_returnsAttemptIdWhenCasSucceeds() {
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(1);

        assertNotNull(state.markProcessing(301L));
    }

    @Test
    void markProcessing_returnsNullWhenAlreadyTaken() {
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(0);

        assertNull(state.markProcessing(301L));
    }

    @Test
    void updateStage_returnsFalseWhenAttemptFenced() {
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(0);

        assertFalse(state.updateStage(301L, "stale-attempt", ResumeOptimization.STAGE_MATCHING));
    }

    @Test
    void updateStage_returnsTrueWhenCurrentAttempt() {
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(1);

        assertTrue(state.updateStage(301L, "current-attempt", ResumeOptimization.STAGE_MATCHING));
    }

    @Test
    void resetStaleProcessing_reflectsUpdateResult() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(10);
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(1);
        assertTrue(state.resetStaleProcessing(301L, staleBefore, 3));

        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(0);
        assertFalse(state.resetStaleProcessing(301L, staleBefore, 3));
    }

    @Test
    void failExhaustedProcessing_reflectsUpdateResult() {
        LocalDateTime staleBefore = LocalDateTime.now().minusMinutes(10);
        when(mapper.update(any(ResumeOptimization.class), any())).thenReturn(1);

        assertTrue(state.failExhaustedProcessing(301L, staleBefore, 3, "多次恢复失败"));
    }
}
