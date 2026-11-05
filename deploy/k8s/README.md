# k8s 清单样例（阶段 10）

这里给出**可直接套用的最小骨架**；生产请按实际集群补充资源限制、探针、PDB、HPA 与
NetworkPolicy。中间件（MySQL/Redis/RabbitMQ/Nacos）建议使用托管服务或独立集群，
不在本目录重复部署。

## 前置

1. 构建镜像（每个服务一个 jar，镜像内固定路径 `/app/app.jar`）：

   ```bash
   docker build -t career-pilot/career-job:latest -f k8s/Dockerfile --build-arg JAR=career-pilot-server/career-job-service/target/career-job.jar .
   # 其余服务同理，替换 JAR 与 tag
   ```

   本目录的 `Dockerfile` 使用 `eclipse-temurin:21-jre`，只复制 jar。

2. 创建命名空间与配置：

   ```bash
   kubectl apply -f namespace.yaml
   kubectl -n career-pilot create configmap career-common \
     --from-literal=NACOS_ADDR=nacos:8848 \
     --from-literal=REDIS_HOST=redis \
     --from-literal=RABBITMQ_HOST=rabbitmq \
     --from-literal=DB_USERNAME=root
   kubectl -n career-pilot create secret generic career-secrets \
     --from-literal=DB_PASSWORD='<MySQL 密码>' \
     --from-literal=APP_JWT_SECRET='<JWT 密钥>' \
     --from-literal=DEEPSEEK_API_KEY='<DeepSeek Key>' \
     --from-literal=APP_LLM_ENCRYPTION_KEY='<加密主密钥>'
   ```

3. 按服务修改 `career-job.yaml` 中的 `SPRING_DATASOURCE_URL` 与镜像 tag，复制出其余
   8 个 Deployment/Service（命名与端口见 `deploy/README.md`）。

## 探针

所有服务都放行了 `/actuator/health`、`/actuator/health/**`（liveness/readiness）与
`/actuator/prometheus`：

```yaml
livenessProbe:
  httpGet: { path: /actuator/health/liveness, port: http }
readinessProbe:
  httpGet: { path: /actuator/health/readiness, port: http }
```

## 发布顺序

1. 中间件与 Nacos；
2. career-ai → career-job/resume → career-match → career-interview → career-agent；
3. career-pilot-server（单体，Dashboard）；
4. career-gateway（最后切流）。
