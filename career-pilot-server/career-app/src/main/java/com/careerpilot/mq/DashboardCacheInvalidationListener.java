package com.careerpilot.mq;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.careerpilot.service.DashboardCache;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Dashboard 缓存失效消费者（阶段 10）。
 *
 * <p>只读取事件信封的 {@code userId}（消息契约 §2.7.3），清掉该用户的 30 秒缓存；
 * 解析失败或缺少 userId 的消息记录后丢弃（重试也不会成功，避免毒消息循环）。
 */
@Component
@ConditionalOnProperty(prefix = "app.dashboard", name = "event-invalidation-enabled",
        havingValue = "true", matchIfMissing = true)
public class DashboardCacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(DashboardCacheInvalidationListener.class);

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DashboardCache dashboardCache;

    public DashboardCacheInvalidationListener(DashboardCache dashboardCache) {
        this.dashboardCache = dashboardCache;
    }

    @RabbitListener(queues = "${app.dashboard.mq.queue:cp.dev.dashboard.invalidate.q}")
    public void onEvent(Message message) {
        String body = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode userId = root.get("userId");
            if (userId == null || !userId.canConvertToLong()) {
                log.warn("Dashboard 缓存失效事件缺少 userId，已丢弃：{}", preview(body));
                return;
            }
            dashboardCache.invalidate(userId.asLong());
            log.info("Dashboard 缓存已失效：userId={} eventType={}",
                    userId.asLong(), root.path("eventType").asString(""));
        }
        catch (RuntimeException ex) {
            log.warn("Dashboard 缓存失效事件解析失败，已丢弃：{}", preview(body));
        }
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 200 ? flat.substring(0, 200) + "…" : flat;
    }
}
