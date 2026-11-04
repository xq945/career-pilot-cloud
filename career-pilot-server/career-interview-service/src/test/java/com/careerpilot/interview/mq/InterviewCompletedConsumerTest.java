package com.careerpilot.interview.mq;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import com.careerpilot.entity.MqConsumedMessage;
import com.careerpilot.mapper.MqConsumedMessageMapper;
import com.careerpilot.security.CurrentUser;
import com.careerpilot.service.InterviewSummaryService;

import tools.jackson.databind.json.JsonMapper;

/**
 * 阶段 8：interview.completed 消费者。
 *
 * <p>三条护栏：**已消费消息跳过**（幂等）、**消费期间建立用户身份**（AI 调用用用户默认模型）、
 * 成功后写幂等记录。
 */
class InterviewCompletedConsumerTest {

    private InterviewSummaryService summaryService;

    private MqConsumedMessageMapper consumedMessageMapper;

    private InterviewCompletedConsumer consumer;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();

    @BeforeEach
    void setUp() {
        summaryService = mock(InterviewSummaryService.class);
        consumedMessageMapper = mock(MqConsumedMessageMapper.class);
        consumer = new InterviewCompletedConsumer(summaryService, consumedMessageMapper, jsonMapper);
    }

    @Test
    void onMessage_generatesSummaryWithUserIdentityAndMarksConsumed() {
        when(consumedMessageMapper.selectById("m-1")).thenReturn(null);
        when(summaryService.generateForSession(1L, 10L)).thenReturn("AI");

        consumer.onMessage(message("m-1", 1L, 10L));

        verify(summaryService).generateForSession(1L, 10L);
        verify(consumedMessageMapper).insert(any(MqConsumedMessage.class));
    }

    @Test
    void onMessage_duplicateMessage_skipsWithoutModelCall() {
        when(consumedMessageMapper.selectById("m-1"))
                .thenReturn(new MqConsumedMessage("m-1", InterviewCompletedConsumer.CONSUMER,
                        LocalDateTime.now()));

        consumer.onMessage(message("m-1", 1L, 10L));

        verifyNoInteractions(summaryService);
        verify(consumedMessageMapper, never()).insert(any(MqConsumedMessage.class));
    }

    @Test
    void onMessage_setsCurrentUserDuringGeneration() {
        when(consumedMessageMapper.selectById("m-1")).thenReturn(null);
        doAnswer(invocation -> {
            assertEquals(1L, CurrentUser.idOrNull(),
                    "消费者必须按消息 userId 建立身份，否则 AiCallTemplate 回退平台默认模型");
            return "AI";
        }).when(summaryService).generateForSession(eq(1L), eq(10L));

        consumer.onMessage(message("m-1", 1L, 10L));

        verify(summaryService).generateForSession(1L, 10L);
    }

    private Message message(String messageId, Long userId, Long sessionId) {
        InterviewCompletedEvent event = new InterviewCompletedEvent(
                messageId, "interview.completed", "2026-11-04T08:00:00Z", userId, sessionId,
                "INTERVIEW_SESSION", 1, "trace-1",
                new InterviewCompletedEvent.Payload(sessionId, new BigDecimal("85.00")));
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(messageId);
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(jsonMapper.writeValueAsString(event).getBytes(StandardCharsets.UTF_8),
                properties);
    }
}
