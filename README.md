# 智能求职辅助平台

基于 Spring AI 的智能体应用开发。

面向个人求职者，提供简历管理、岗位管理、简历定向优化、AI 面试和求职智能体等功能。

## 技术栈

| 层次 | 选型 | 版本 |
|---|---|---|
| 前端 | Vue 3 + Vite + Vue Router + Pinia | 官方稳定版 |
| UI | Element Plus | 官方稳定版 |
| HTTP | Axios | 官方稳定版 |
| 后端 | Java + Spring Boot | Java 21 + Spring Boot 4.0.9 |
| AI 集成 | Spring AI | 2.0.1 |
| 模型 | DeepSeek API | `deepseek-flash` |
| 数据库 | MySQL | 8.0+（推荐 8.4 LTS） |
| ORM | MyBatis-Plus | 3.5.17 |
| 认证 | Spring Security + jjwt | 7.x + 0.12.7 |
| 文件解析 | Apache Tika | 3.3.2 |

## 目录结构

```text
career-pilot-ai/
├─ career-pilot-server/   后端（Spring Boot 4.0.9 + Java 21，Maven 多模块）
│  ├─ career-common/      共享内核（统一响应、异常、分页、Redis Stream 模板）
│  ├─ career-token/       用户 JWT 与网关内部令牌（无 Servlet 依赖）
│  ├─ career-storage/     文件存储抽象（本地 / OSS）
│  ├─ career-security/    JWT 过滤器与当前用户上下文
│  ├─ career-ai-core/     AI 基础设施（AiCallTemplate/结构化输出/Provider 实现）
│  ├─ career-ai-service/  AI 服务（8087，Provider 管理 + 模型调用代理）
│  ├─ career-domain-*/    业务域（auth / resume / job / match / interview / agent）
│  ├─ career-job-client/  岗位服务远程客户端（Feign）
│  ├─ career-job-service/ 岗位服务（8083，岗位 CRUD/进度/分析）
│  ├─ career-resume-client/  简历服务远程客户端（Feign）
│  ├─ career-resume-service/ 简历服务（8082，简历 CRUD/上传/文本提取/分析）
│  ├─ career-match-client/   匹配/优化服务远程客户端（Feign）
│  ├─ career-match-service/  匹配与优化服务（8084，匹配/优化/Redis Stream worker）
│  ├─ career-app/         剩余业务单体（8091）
│  ├─ career-auth-service/ 认证服务（8081，验证码/JWT/资料/头像）
│  └─ career-gateway/     网关（8080，JWT 验签 + Nacos 路由）
├─ career-pilot-web/      前端（Vue 3 + Vite）
├─ prototype/             前端高保真原型（HTML，双击 index.html 即可查看）
├─ tools/                 开发辅助工具：brand/ 品牌资源生成与校验、e2e/ 浏览器端到端验证、
│                         verify/ 接口层验收脚本、seed/ 演示数据种子（`node tools/seed/demo-data.mjs`）
├─ Mysql/                 数据库初始化脚本
└─ docs/                  设计与接口文档（本地维护，不入库）
```

## 项目进度

第一版（M1–M9）与后续增强（M11–M21：简历定向优化、面试接入工作流、工作台聚合与版本对比、AI 基建与多模型 Provider、面试域升级、创建优化异步化、面试日程、登录图形验证码、岗位投递入口、岗位求职进度）均已完成并逐一实测，验证过程与结果见 `docs/验证记录.md`：

| 里程碑 | 内容 | 状态 |
|---|---|---|
| M1 | 认证与工程骨架 | 已完成 |
| M2 | 个人中心（资料、头像） | 已完成 |
| M3 | 简历材料管理（上传 / 文本提取 / 列表 / 删除） | 已完成 |
| M4 | AI 基础设施 + 简历分析 | 已完成 |
| M5 | 岗位模块（含分析过期判定） | 已完成 |
| M6 | 匹配模块（确定性评分 + 模型解释） | 已完成 |
| M7 | 面试模块（出题 / 评价 / 报告与总评） | 已完成 |
| M8 | 求职智能体（工具调用 + 对话与会话记录） | 已完成 |
| M9 | 聚合与收尾（仪表盘实装 + 全链路验收与安全专项） | 已完成 |
| M11 | 简历定向优化（匹配内核 + 按岗位改写简历 + 编辑/评估/DOCX 导出） | 已完成 |
| M12 | 面试接入工作流（携带简历/优化上下文出题，来源可追溯） | 已完成 |
| M13 | 工作台聚合与版本对比（仪表盘最近任务时间线 + 优化稿行内 diff） | 已完成 |
| M14 | AI 基建（多模型 Provider：结构化修复重试 + 用户级模型服务配置与运行时切换） | 已完成 |
| M15 | 面试域升级（Skill 驱动出题 + 评估可靠性：总评可重新生成、失败降级标注） | 已完成 |
| M16 | 创建优化异步化（Redis 任务队列 + 卡住任务恢复，前端轮询进度） | 已完成 |
| M17 | 面试日程（邀请文本解析 + 列表/日历双视图 + 状态流转与定时过期） | 已完成 |
| M18 | 登录图形验证码（一次性 + 容量上限的本地缓存，既有认证链路零改动） | 已完成 |
| M19 | 岗位投递入口（用户自填投递链接 + 列表「投递」按钮，仅跳转） | 已完成 |
| M20 | 管理页收敛（删除简历/岗位管理页的分析入口与分析状态标签） | 已完成 |
| M21 | 岗位求职进度（八态跟踪：待投递 → 已投递 → 笔试/一面/二面/HR 面 → Offer/已拒绝，列表行内切换） | 已完成 |

演示数据一键就绪（工作台核心流程五步全亮）：

```bash
node tools/seed/demo-data.mjs --matches 2 --optimizations 1 --interviews 1   # 账号 demo / demo1234
```

## 快速开始

### 前置条件

| 组件 | 要求 | 本项目验证环境 |
|---|---|---|
| JDK | 21 | 21.0.12 |
| Maven | 3.9+ | 3.9.16 |
| Node.js | 20+ | 24.21.0 |
| MySQL | 8.0+ | 8.0.46 |
| Redis | 6.2+（**M16** 优化任务队列；**阶段 4 起** auth 验证码与 JWT 黑名单也使用） | 本地服务 `6379` |
| Nacos | 3.x（**阶段 3 起**，服务注册发现；无 Nacos 时可用 `NACOS_ENABLED=false` 直连单体） | 3.0.3，`8848` |

### 1. 初始化数据库

```bash
mysql -u root -p < Mysql/career_pilot_init.sql
```

脚本创建 `career_pilot` 库（utf8mb4 / utf8mb4_0900_ai_ci）与 13 张表，内容由《数据库设计》的 DDL 生成，与设计文档保持同步。

阶段 4 起，认证数据拆到独立 schema（首次部署需再执行一次）：

```bash
mysql -u root -p < Mysql/migration-stage4-auth.sql
```

该脚本创建 `cp_auth`、复制 `sys_user`，并删除 `career_pilot` 各业务表对 `sys_user` 的跨库外键。

阶段 6 起，岗位数据拆到 `cp_job`（首次部署再执行一次）：

```bash
mysql -u root -p < Mysql/migration-stage6-job.sql
```

阶段 6b 起，简历数据拆到 `cp_resume`（首次部署再执行一次）：

```bash
mysql -u root -p < Mysql/migration-stage6b-resume.sql
```

该脚本创建 `cp_resume`、复制 `resume` / `resume_analysis`，并删除 `career_pilot` 下匹配、面试、优化记录对简历表的跨库外键。

阶段 7 起，匹配与优化数据拆到 `cp_match`（首次部署再执行一次）：

```bash
mysql -u root -p < Mysql/migration-stage7-match.sql
```

该脚本创建 `cp_match`、复制 `match_record` / `resume_optimization`，新增输入快照与冗余版本号列，并删除面试表对优化记录的跨库外键。

### 2. 配置后端

复制配置模板并填入真实值：

```bash
cd career-pilot-server/career-app/src/main/resources
cp application-local.yml.example application-local.yml
```

需填写三项：

| 配置项 | 说明 |
|---|---|
| `spring.datasource.password` | MySQL 密码 |
| `spring.ai.deepseek.api-key` | DeepSeek API Key —— **缺失会导致应用启动失败**，非仅调用时失败 |
| `app.jwt.secret` | JWT 签名密钥，用 `openssl rand -base64 48` 生成 |

**Redis 与「创建优化」异步链路（M16）**：默认连接 `redis://localhost:6379`（配置项 `app.optimization.async.redis-address`）。

- 开启异步时 Redis 是**启动前置依赖**——应用启动会主动探活，连不上即启动失败（避免"应用起来了但任务永远不执行"）；
- 无 Redis 环境可在 `application.yml` 中设 `app.optimization.async.enabled: false`：创建优化退化为**同步执行**（与旧版行为一致，等待 25–80 秒后返回），其余功能不受影响。

`application-local.yml` 已被 `.gitignore` 忽略，不会进入版本库。

### 3. 启动后端

先确认 Nacos（`127.0.0.1:8848`）与 Redis（`127.0.0.1:6379`）已启动。

```bash
cd career-pilot-server
mvn clean package

# 1) AI 服务：8087（Provider 管理 + 模型调用代理）
#    DEEPSEEK_API_KEY 与 APP_LLM_ENCRYPTION_KEY 从 application-local.yml 对应项读取
DB_PASSWORD='<MySQL 密码>' APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  DEEPSEEK_API_KEY='<DeepSeek Key>' APP_LLM_ENCRYPTION_KEY='<LLM Key 加密主密钥>' \
  java -jar career-ai-service/target/career-ai.jar

# 2) 岗位服务：8083（岗位 CRUD / 求职进度 / 岗位分析）
DB_PASSWORD='<MySQL 密码>' APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  java -jar career-job-service/target/career-job.jar

# 3) 简历服务：8082（简历 CRUD / 上传与文本提取 / 简历分析）
#    文件存储与 auth 服务共享 app.file.storage-root（默认 ${user.home}/career-pilot-uploads）
DB_PASSWORD='<MySQL 密码>' APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  java -jar career-resume-service/target/career-resume.jar

# 4) 匹配与优化服务：8084（匹配/优化 + Redis Stream worker）
#    需要 Redis；可再启动实例并设 APP_OPTIMIZATION_ROLE=worker/api 拆分角色
DB_PASSWORD='<MySQL 密码>' APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  java -jar career-match-service/target/career-match.jar

# 5) 认证服务：8081（验证码/JWT/资料/头像）
#    DB_PASSWORD 为 MySQL 密码；APP_JWT_SECRET 与 application-local.yml 的 app.jwt.secret 相同
DB_PASSWORD='<MySQL 密码>' APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  java -jar career-auth-service/target/career-auth.jar

# 6) 剩余业务单体：8091（另开终端，自动读取 career-app 的 application-local.yml）
java -jar career-app/target/career-pilot-server.jar

# 7) 网关：8080（另开终端）
GATEWAY_PORT=8080 APP_JWT_SECRET='<与 app.jwt.secret 相同的值>' \
  java -jar career-gateway/target/career-gateway.jar
```

> 阶段 5 起，AI 服务与单体（Agent 工具调用）暂时共享 `career_pilot.llm_provider`；Agent 拆分后再迁移到 `cp_ai`。

服务地址 `http://localhost:8080`（经网关）；单体直连为 `http://localhost:8081`。本机 8080 被其他程序占用时，把 `GATEWAY_PORT` 改为 18080 等空闲端口，并把前端 `VITE_API_BASE_URL` 指向同一端口。

> **务必用 JDK 21 运行。** 若本机 `JAVA_HOME` 指向其他版本，注意 `java -jar` 走的是 PATH 解析而非 `JAVA_HOME`，此时应使用完整路径：`/path/to/jdk21/bin/java -jar career-app/target/career-pilot-server.jar`。否则会报 `UnsupportedClassVersionError`。

验证是否正常：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"test01","email":"test01@example.com","password":"Test1234"}'
```

返回 `{"code":0,...}` 即表示服务与数据库均正常。

### 4. 启动前端

```bash
cd career-pilot-web
npm install
npm run dev
```

访问 `http://localhost:5173`。

## 配置说明

### 敏感配置与注入方式

以下配置**不进入仓库**，通过 `application-local.yml`（推荐）或环境变量注入：

| 配置项 | 对应环境变量 | 用途 |
|---|---|---|
| `spring.datasource.password` | `DB_PASSWORD` | MySQL 连接密码 |
| `spring.ai.deepseek.api-key` | `DEEPSEEK_API_KEY` | 大模型调用凭据 |
| `app.jwt.secret` | — | JWT 签名密钥（HS256，不少于 32 字节） |
| `app.file.oss.access-key-id` / `access-key-secret` | `OSS_ACCESS_KEY_ID` / `OSS_ACCESS_KEY_SECRET` | 阿里云 OSS 凭据（仅 `app.file.storage-type=oss` 时使用） |

`application.yml` 中只保留非敏感默认值。

### 可调参数

| 项 | 默认值 | 配置项 |
|---|---|---|
| AI 服务端口 | 8087 | `server.port` / `AI_PORT` |
| 岗位服务端口 | 8083 | `server.port` / `JOB_PORT` |
| 简历服务端口 | 8082 | `server.port` / `RESUME_PORT` |
| 匹配服务端口 | 8084 | `server.port` / `MATCH_PORT` |
| 优化 Worker 角色 | `all` | `app.optimization.role` / `APP_OPTIMIZATION_ROLE`（`api` 不消费任务） |
| auth 服务端口 | 8081 | `server.port` / `AUTH_PORT` |
| 剩余单体端口 | 8091 | `server.port` / `SERVER_PORT` |
| 网关端口 | 8080 | `server.port` / `GATEWAY_PORT` |
| Nacos 地址 | 127.0.0.1:8848 | `spring.cloud.nacos.discovery.server-addr` / `NACOS_ADDR` |
| 前端开发端口 | 5173 | `career-pilot-web/vite.config.js` |
| 前端请求的 API 地址 | `http://localhost:8080/api` | `career-pilot-web/.env.development` 的 `VITE_API_BASE_URL`（本地覆盖用 `.env.local`） |
| 允许的跨域来源 | `http://localhost:5173` | `app.cors.allowed-origins` |
| JWT 有效期 | 7200 秒 | `app.jwt.expiration-seconds` |
| 模型名 | `deepseek-flash` | `spring.ai.deepseek.chat.options.model` |
| AI 调用超时 | 连接 10 秒 / 读取 60 秒 | `spring.http.clients.connect-timeout` / `read-timeout`（调小可用于构造 50200 场景） |
| 模型调用重试 | 2 次尝试（1 次初始 + 1 次重试） | `spring.ai.retry.max-attempts`（**必须显式配置**：Spring AI 默认为 10 次，会把单次超时放大到分钟级） |
| 文件存储实现 | `local`（本地磁盘） | `app.file.storage-type`（`oss` 切换阿里云 OSS，见配置模板） |
| 本地存储根目录 | `${user.home}/career-pilot-uploads` | `app.file.storage-root`（须位于项目运行目录之外） |
| 单文件上传上限 | 10 MB（简历；容器层与业务层双重校验） | `spring.servlet.multipart.max-file-size`（头像另有 2 MB 业务限制；配套的 `max-request-size` 与 `server.tomcat.max-swallow-size` 为 50 MB，**调大文件上限时需同步调整**，原因见 `application.yml` 注释） |

## 文档

设计与接口文档位于本地 `docs/` 目录，不进入版本库（已列入 `.gitignore`），因此下表只作本地检索索引，不提供跳转链接。

### 设计文档（实现依据）

| 文档 | 说明 |
|---|---|
| 需求规格说明书 | 用户角色、功能需求（FR 编号）、MVP 边界与非功能需求 |
| 系统架构设计 | 技术选型、分层职责、安全与部署约定 |
| 数据库设计 | 表结构、字段与删除策略 |
| API接口设计 | REST 接口与统一响应结构 |
| Agent设计 | 智能体、工具调用与 Prompt 约定 |
| 前端原型设计 | 页面结构、交互流程、视觉规范与页面↔接口映射 |
| 测试与验收方案 | 测试分层、用例矩阵（含越权矩阵）与验收索引 |
| 品牌设计 | Logo 形象、配色、资源清单、生成与验证方式 |

### 计划与记录

| 文档 | 说明 |
|---|---|
| 开发顺序 | M1–M9 里程碑、任务拆分与验收标准（执行基线，M1–M9 已全部完成） |
| 验证记录 | 各功能实现后的实测过程与结果 |
| 问题记录 | 实施中实际遇到的问题，按类别归档（`docs/问题记录/`） |

### 审查档案

| 文档 | 说明 |
|---|---|
| 设计审查报告 | 第一、二轮审查 —— 问题已全部闭环（历史） |
| API接口审查报告 | 第三轮审查（A01–A34）—— 已全部落实（历史） |
| 开发文档全量审查报告 | 第四轮全量审查（R01–R29）—— 已全部落实（历史） |

> 以上均为 `docs/` 目录下的 Markdown 文件。各轮修订前的文档备份位于 `docs/.backup-v1/`、`docs/.backup-v2/`、`docs/.backup-v3/`（按修订轮次命名），仅供追溯，不参与日常开发。
