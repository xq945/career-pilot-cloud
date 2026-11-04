package com.careerpilot.interview.mq;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.careerpilot.entity.MqConsumedMessage;
import com.careerpilot.mapper.MqConsumedMessageMapper;
import com.careerpilot.security.AuthenticatedUser;
import com.careerpilot.service.InterviewSummaryService;

import tools.jackson.databind.ObjectMapper;

/**
 * interview.completed 消费者（阶段 8）：异步生成总评并回写 {@code summary_source}。
 *
 * <p>语义：
 * <ul>
 *   <li>**幂等**：处理前查 {@code mq_consumed_message}，处理成功后写入（主键冲突即重复投递）；</li>
 *   <li>**身份**：消息的 userId 显式写入 SecurityContext——消费者没有 HTTP 上下文，
 *       不建立身份会让 {@code AiCallTemplate} 回退平台默认模型（阶段 1 · B1 同类缺陷）；</li>
 *   <li>**失败**：模型失败在 {@link InterviewSummaryService} 内降级为 DEGRADED（不重试）；
 *       DB/基础设施异常向上抛出，由 listener 重试 3 次后进入 DLQ。</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(prefix = "app.interview.summary", name = "async-enabled",
        havingValue = "true")
public class InterviewCompletedConsumer {

    private static final Logger log = LoggerFactory.getLogger(InterviewCompletedConsumer.class);

    /** 幂等记录的消费者名 */
    static final String CONSUMER = "career-interview.summary";

    private final InterviewSummaryService summaryService;

    private final MqConsumedMessageMapper consumedMessageMapper;

    private final ObjectMapper objectMapper;

    public InterviewCompletedConsumer(InterviewSummaryService summaryService,
                                      MqConsumedMessageMapper consumedMessageMapper,
                                      ObjectMapper objectMapper) {
        this.summaryService = summaryService;
        this.consumedMessageMapper = consumedMessageMapper;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = "${app.interview.mq.queue:cp.dev.interview.completed.q}")
    public void onMessage(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        InterviewCompletedEvent event = objectMapper.readValue(body, InterviewCompletedEvent.class);
        if (!"interview.completed".equals(event.eventType())
                || event.payload() == null || event.payload().sessionId() == null
                || event.userId() == null) {
            // 契约不符的消息重试也不会成功：记录后丢弃，避免毒消息占满队列
            log.warn("丢弃无法识别的 interview.completed 消息：messageId={}",
                    event.messageId());
            return;
        }
        String messageId = event.messageId() == null
                ? message.getMessageProperties().getMessageId() : event.messageId();
        if (messageId == null) {
            log.warn("丢弃缺少 messageId 的 interview.completed 消息（无法保证幂等）");
            return;
        }
        if (consumedMessageMapper.selectById(messageId) != null) {
            log.info("重复消息已消费，跳过：messageId={} sessionId={}",
                    messageId, event.payload().sessionId());
            return;
        }

        AuthenticatedUser principal = new AuthenticatedUser(event.userId(), "system");
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
        SecurityContextHolder.setContext(context);
        try {
            Long sessionId = event.payload().sessionId();
            String source = summaryService.generateForSession(event.userId(), sessionId);
            try {
                consumedMessageMapper.insert(new MqConsumedMessage(
                        messageId, CONSUMER, LocalDateTime.now().withNano(0)));
            }
            catch (DuplicateKeyException ex) {
                // 并发重复投递：另一消费者已完成，总评写入本身是幂等的（覆盖同值）
                log.info("并发重复消息：messageId={} sessionId={}", messageId, sessionId);
            }
            log.info("总评消息处理完成：messageId={} sessionId={} source={}",
                    messageId, sessionId, source);
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }
}
