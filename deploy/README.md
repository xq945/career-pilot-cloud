# CareerPilot 部署（阶段 10）

本目录提供 **docker-compose 一键部署**（本地/答辩演示）与 **k8s 清单样例**（生产骨架）。
默认端口：网关 8080、auth 8081、resume 8082、job 8083、match 8084、interview 8085、agent 8086、ai 8087、单体 8091；中间件 MySQL 3306、Redis 6379、RabbitMQ 5672/15672、Nacos 8848/8849。

## 1. docker compose（推荐用于演示）

### 前置

1. JDK 21 + Maven；
2. 在 `career-pilot-server/` 执行：

   ```bash
   mvn clean package
   ```

   确认以下 jar 存在（compose 直接挂载，不重复构建镜像）：

   ```text
   career-app/target/career-pilot-server.jar
   career-auth-service/target/career-auth.jar
   career-ai-service/target/career-ai.jar
   career-job-service/target/career-job.jar
   career-resume-service/target/career-resume.jar
   career-match-service/target/career-match.jar
   career-interview-service/target/career-interview.jar
   career-agent-service/target/career-agent.jar
   career-gateway/target/career-gateway.jar
   ```

3. 本目录复制环境变量并填写：

   ```bash
   cd deploy
   cp .env.example .env
   # 编辑 .env：APP_JWT_SECRET、DEEPSEEK_API_KEY、APP_LLM_ENCRYPTION_KEY、MySQL 密码
   ```

### 启动

```bash
cd deploy
docker compose up -d
docker compose ps
```

首次启动 MySQL 会按文件名顺序执行 `../Mysql` 下的脚本：`career_pilot_init.sql` →
`migration-stage4-auth` → `stage6-job` → `stage6b-resume` → `stage7-match` →
`stage8-interview` → `stage9-agent`。**只有数据卷为空时才会执行**；重置演示数据：

```bash
docker compose down -v   # 会删除 mysql-data 与 uploads
docker compose up -d
```

### 验证

```bash
# 健康检查（服务已放行 /actuator/health 与 /actuator/prometheus）
curl http://localhost:8080/actuator/health
curl http://localhost:8087/actuator/health

# Prometheus 指标
curl http://localhost:8083/actuator/prometheus | head

# 登录（演示环境 APP_CAPTCHA_ENABLED=false）
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"account":"<用户名>","password":"<密码>"}'

# 工作台（并发聚合 + 30s 缓存 + 分区降级）
curl http://localhost:8080/api/dashboard/summary -H "Authorization: Bearer <token>"
```

Nacos 控制台：<http://localhost:8849>（standalone，关闭鉴权，仅限内网演示）。
RabbitMQ 控制台：<http://localhost:15672>。

### 可观测性

- 每个服务暴露 `/actuator/prometheus`（Micrometer + Prometheus registry，标签
  `application=<spring.application.name>`）；
- `management.endpoint.health.probes.enabled=true`，支持 `/actuator/health/liveness|readiness`；
- 日志级别 pattern 带 traceId：`%5p [%X{traceId:-}]`；网关生成 `X-Trace-Id`，
  各服务 `TraceIdFilter` 写入 MDC，Feign 拦截器继续透传；
- 建议 Prometheus 抓取配置：

  ```yaml
  scrape_configs:
    - job_name: career-pilot
      metrics_path: /actuator/prometheus
      static_configs:
        - targets: ["career-gateway:8080","career-auth:8081","career-resume:8082",
                    "career-job:8083","career-match:8084","career-interview:8085",
                    "career-agent:8086","career-ai:8087","career-pilot-server:8091"]
  ```

## 2. 事件与缓存

- `interview.completed`：面试服务在最后一题提交事务内写 `outbox_event`，调度器投递；
  Dashboard 的 `cp.dev.dashboard.invalidate.q` 与面试自己的队列**各自收到一份**；
- `optimization.completed`：队列已绑定，生产端尚未接入（优化完成仍靠 30s TTL 兜底）；
- 缓存关闭：`APP_DASHBOARD_EVENT_INVALIDATION_ENABLED=false`（无 RabbitMQ 的单体环境）。

## 3. k8s

见 [k8s/README.md](k8s/README.md)。核心思路：镜像内只放 jar，配置走 ConfigMap/Secret，
`SPRING_DATASOURCE_URL` 指向托管 MySQL；Nacos/Redis/RabbitMQ 使用生产集群或云服务。

## 4. 回滚

- Dashboard 仍在单体（阶段 10 未新增第 9 个部署单元）：回滚只需停掉新增的 Prometheus/事件
  配置，或把 `app.dashboard.event-invalidation-enabled=false`；
- 各业务服务回滚仍按阶段 4–9 的说明（路由切回单体 + `app.*.mode=local`）。
