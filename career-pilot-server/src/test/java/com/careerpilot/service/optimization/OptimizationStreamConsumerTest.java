package com.careerpilot.service.optimization;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.redisson.api.stream.StreamMessageId;

import com.careerpilot.common.BusinessException;
import com.careerpilot.common.async.AbstractStreamConsumer;
import com.careerpilot.common.constant.AsyncTaskStreamConstants;
import com.careerpilot.entity.ResumeOptimization;
import com.careerpilot.infrastructure.redis.RedisStreamService;
import com.careerpilot.mapper.ResumeOptimizationMapper;
import com.careerpilot.service.optimization.OptimizationStreamProducer.OptimizationTask;

/**
 * 异步失败落点（第三阶段/修复 C）：消费者把任务执行异常交给既有状态机落 FAILED。
 */
class OptimizationStreamConsumerTest {

    @Test
    void markFailed_delegatesToStateServiceWithReason() {
        OptimizationTaskStateService stateService = mock(OptimizationTaskStateService.class);
        OptimizationStreamConsumer consumer = new OptimizationStreamConsumer(
                mock(RedisStreamService.class),
                mock(ResumeOptimizationMapper.class),
                stateService,
                mock(OptimizationTaskRunner.class));
        String reason = "生成的优化稿超过长度限制（8000 字符），未保存为有效优化结果";

        consumer.markFailed(new OptimizationTask(301L, 7L), "attempt-1", reason);

        verify(stateService).fail(301L, "attempt-1", reason);
    }

    /**
     * 直接走 {@link AbstractStreamConsumer#handleMessage}（Redis Stream 消息处理入口）：
     * processBusiness 抛覆盖校验失败 → markFailed → stateService.fail，且从不 complete。
     */
    @Test
    void handleMessage_coverageFailure_marksFailedAndNeverCompletes() throws Exception {
        RedisStreamService redis = mock(RedisStreamService.class);
        ResumeOptimizationMapper mapper = mock(ResumeOptimizationMapper.class);
        OptimizationTaskStateService state = mock(OptimizationTaskStateService.class);
        OptimizationTaskRunner runner = mock(OptimizationTaskRunner.class);

        ResumeOptimization record = new ResumeOptimization();
        record.setId(301L);
        record.setStatus(ResumeOptimization.STATUS_PENDING);
        when(mapper.selectById(301L)).thenReturn(record);
        when(state.markProcessing(301L)).thenReturn("attempt-1");
        String reason = "优化结果未通过事实校验，未保存：changes[0] 涉及跨章节移动事实（当前版本不支持）";
        doThrow(BusinessException.aiInvalidOutput(reason)).when(runner).run(301L, "attempt-1");

        OptimizationStreamConsumer consumer = new OptimizationStreamConsumer(
                redis, mapper, state, runner);
        Method method = AbstractStreamConsumer.class.getDeclaredMethod(
                "handleMessage", StreamMessageId.class, Map.class);
        method.setAccessible(true);
        Map<String, String> data = Map.of(
                AsyncTaskStreamConstants.FIELD_OPTIMIZATION_ID, "301",
                AsyncTaskStreamConstants.FIELD_USER_ID, "7");

        method.invoke(consumer, new StreamMessageId(0, 1), data);

        verify(state).fail(eq(301L), eq("attempt-1"), contains("事实校验"), nullable(String.class));
        verify(state, never()).complete(any(), any(), any(), any(), any(), any());
    }

    /** 日期事件角色校验失败同样走 markFailed → fail，且不写 SUCCESS/产物。 */
    @Test
    void handleMessage_dateRoleFailure_marksFailedAndNeverCompletes() throws Exception {
        RedisStreamService redis = mock(RedisStreamService.class);
        ResumeOptimizationMapper mapper = mock(ResumeOptimizationMapper.class);
        OptimizationTaskStateService state = mock(OptimizationTaskStateService.class);
        OptimizationTaskRunner runner = mock(OptimizationTaskRunner.class);

        ResumeOptimization record = new ResumeOptimization();
        record.setId(301L);
        record.setStatus(ResumeOptimization.STATUS_PENDING);
        when(mapper.selectById(301L)).thenReturn(record);
        when(state.markProcessing(301L)).thenReturn("attempt-1");
        String reason = "优化结果未通过事实校验，未保存：优化稿第 1 块的日期事件角色与原文不一致";
        doThrow(BusinessException.aiInvalidOutput(reason)).when(runner).run(301L, "attempt-1");

        OptimizationStreamConsumer consumer = new OptimizationStreamConsumer(
                redis, mapper, state, runner);
        Method method = AbstractStreamConsumer.class.getDeclaredMethod(
                "handleMessage", StreamMessageId.class, Map.class);
        method.setAccessible(true);
        Map<String, String> data = Map.of(
                AsyncTaskStreamConstants.FIELD_OPTIMIZATION_ID, "301",
                AsyncTaskStreamConstants.FIELD_USER_ID, "7");

        method.invoke(consumer, new StreamMessageId(0, 2), data);

        verify(state).fail(eq(301L), eq("attempt-1"), contains("日期"), nullable(String.class));
        verify(state, never()).complete(any(), any(), any(), any(), any(), any());
    }
}
