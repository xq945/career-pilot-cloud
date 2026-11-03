-- ============================================================
-- 阶段 4：auth 服务独立的数据迁移
-- 日期：2026-11-03
-- 说明：
--   1. 新建 cp_auth，并把 career_pilot.sys_user 复制过去；
--   2. 删除 career_pilot 各业务表对 sys_user 的外键（跨 schema 无法保留）；
--   3. career_pilot.sys_user 保留只读，作为回滚点，不再由应用写入。
-- 仅需执行一次；外键删除重复执行会报 1091，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_auth
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_auth.sys_user LIKE career_pilot.sys_user;

INSERT IGNORE INTO cp_auth.sys_user SELECT * FROM career_pilot.sys_user;

ALTER TABLE career_pilot.resume              DROP FOREIGN KEY fk_resume_user;
ALTER TABLE career_pilot.job                 DROP FOREIGN KEY fk_job_user;
ALTER TABLE career_pilot.match_record        DROP FOREIGN KEY fk_match_user;
ALTER TABLE career_pilot.interview_session   DROP FOREIGN KEY fk_interview_user;
ALTER TABLE career_pilot.agent_session       DROP FOREIGN KEY fk_agent_session_user;
ALTER TABLE career_pilot.agent_chat_message  DROP FOREIGN KEY fk_agent_message_user;
ALTER TABLE career_pilot.resume_optimization DROP FOREIGN KEY fk_resume_optimization_user;
ALTER TABLE career_pilot.llm_provider        DROP FOREIGN KEY fk_llm_provider_user;
ALTER TABLE career_pilot.interview_schedule  DROP FOREIGN KEY fk_schedule_user;
