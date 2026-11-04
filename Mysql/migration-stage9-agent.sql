-- ============================================================
-- 阶段 9：career-agent 独立的数据迁移
-- 日期：2026-11-04
-- 1. 新建 cp_agent，复制 agent_session / agent_chat_message；
-- 2. agent_chat_message 新增 turn_id（单轮对话标识，工具幂等键的组成部分）；
-- 3. career_pilot 的同名表保留只读作为回滚点，并按回滚需要补齐新列。
--
-- CREATE TABLE ... LIKE 不复制外键定义，cp_agent 内无需删除跨库外键
-- （agent 表本身没有外键；userId 是快照列，不建外键）。
-- 仅需执行一次；重复执行时 ADD COLUMN 会报 1060，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_agent
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_agent.agent_session LIKE career_pilot.agent_session;
INSERT IGNORE INTO cp_agent.agent_session SELECT * FROM career_pilot.agent_session;

CREATE TABLE IF NOT EXISTS cp_agent.agent_chat_message LIKE career_pilot.agent_chat_message;
INSERT IGNORE INTO cp_agent.agent_chat_message SELECT * FROM career_pilot.agent_chat_message;

-- 单轮对话标识（UUID 去连字符）；工具调用幂等键 = turnId + toolName + 参数哈希（一期记录日志）
ALTER TABLE cp_agent.agent_chat_message
  ADD COLUMN turn_id VARCHAR(36) NULL AFTER session_id,
  ADD KEY idx_agent_message_turn (session_id, turn_id);

-- 过渡：Agent 的模型调用仍是本地 Tool Calling（方案 §4.3 保留 @Tool 与预算），
-- 需要读取用户级 Provider。Provider 表仍归 career-ai/单体共享的 career_pilot；
-- 在 cp_agent 建只读视图，应用代码继续按 llm_provider 表名读取（阶段 9 过渡方案，
-- 待 Agent 改走模型代理/任务式后再移除）。
CREATE OR REPLACE VIEW cp_agent.llm_provider AS
  SELECT * FROM career_pilot.llm_provider;

-- 回滚兼容：单体 app.agent.mode=local 时仍写 career_pilot，新实体列需同步存在
ALTER TABLE career_pilot.agent_chat_message
  ADD COLUMN turn_id VARCHAR(36) NULL AFTER session_id;
