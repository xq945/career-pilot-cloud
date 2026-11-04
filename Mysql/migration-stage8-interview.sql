-- ============================================================
-- 阶段 8：career-interview 独立的数据迁移
-- 日期：2026-11-04
-- 1. 新建 cp_interview，复制 interview_session / interview_question / interview_schedule；
-- 2. interview_session 新增 context_snapshot（创建时出题上下文的精简快照）；
-- 3. 新建事件 outbox 与消费幂等表（总评异步化）；
-- 4. career_pilot 的同名表保留只读作为回滚点，并按回滚需要补齐新列。
--
-- CREATE TABLE ... LIKE 不复制外键定义：cp_interview 内只重建
-- interview_question → interview_session 的同库外键；对 user/job/resume/optimization
-- 的跨库引用不建外键（阶段 4/6/6b/7 已陆续删除，本阶段不再重复删除）。
-- 仅需执行一次；重复执行时 ADD COLUMN 会报 1060、ADD CONSTRAINT 会报 1826，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_interview
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_interview.interview_session LIKE career_pilot.interview_session;
INSERT IGNORE INTO cp_interview.interview_session SELECT * FROM career_pilot.interview_session;

CREATE TABLE IF NOT EXISTS cp_interview.interview_question LIKE career_pilot.interview_question;
INSERT IGNORE INTO cp_interview.interview_question SELECT * FROM career_pilot.interview_question;

CREATE TABLE IF NOT EXISTS cp_interview.interview_schedule LIKE career_pilot.interview_schedule;
INSERT IGNORE INTO cp_interview.interview_schedule SELECT * FROM career_pilot.interview_schedule;

-- 同库外键（CREATE TABLE ... LIKE 未复制；cp_interview 内两张表主键一致，可直接建）
ALTER TABLE cp_interview.interview_question
  ADD CONSTRAINT fk_interview_question_session
  FOREIGN KEY (session_id) REFERENCES cp_interview.interview_session (id);

-- 创建时写出的出题上下文快照（旧数据为 NULL；重新生成总评不依赖它）
ALTER TABLE cp_interview.interview_session
  ADD COLUMN context_snapshot JSON NULL AFTER optimization_id;

-- 事件投递本地消息表：业务事务内写入，提交后由调度器发送到 RabbitMQ
CREATE TABLE IF NOT EXISTS cp_interview.outbox_event (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  event_id VARCHAR(36) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  aggregate_type VARCHAR(64) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  payload JSON NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
  retry_count INT NOT NULL DEFAULT 0,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  sent_at DATETIME NULL,
  UNIQUE KEY uk_outbox_event (event_id),
  KEY idx_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 消费幂等：messageId 唯一键，保证消息重投不重复生成总评
CREATE TABLE IF NOT EXISTS cp_interview.mq_consumed_message (
  message_id VARCHAR(36) PRIMARY KEY,
  consumer VARCHAR(100) NOT NULL,
  consumed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 回滚兼容：单体 app.interview.mode=local 时仍写 career_pilot，新实体列需同步存在。
-- （outbox_event / mq_consumed_message 只在异步路径使用，单体默认关闭异步，无需回滚建表。）
ALTER TABLE career_pilot.interview_session
  ADD COLUMN context_snapshot JSON NULL AFTER optimization_id;
