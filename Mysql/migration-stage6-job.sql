-- ============================================================
-- 阶段 6：career-job 独立的数据迁移
-- 日期：2026-11-04
-- 1. 新建 cp_job，复制 job / job_analysis；
-- 2. 删除 career_pilot 中对 job / job_analysis 的跨库外键；
-- 3. career_pilot.job / job_analysis 保留只读作为回滚点。
-- 仅需执行一次；外键重复删除会报 1091，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_job
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_job.job LIKE career_pilot.job;
INSERT IGNORE INTO cp_job.job SELECT * FROM career_pilot.job;

CREATE TABLE IF NOT EXISTS cp_job.job_analysis LIKE career_pilot.job_analysis;
INSERT IGNORE INTO cp_job.job_analysis SELECT * FROM career_pilot.job_analysis;

ALTER TABLE career_pilot.match_record        DROP FOREIGN KEY fk_match_job;
ALTER TABLE career_pilot.match_record        DROP FOREIGN KEY fk_match_job_analysis;
ALTER TABLE career_pilot.interview_session   DROP FOREIGN KEY fk_interview_job;
ALTER TABLE career_pilot.resume_optimization DROP FOREIGN KEY fk_resume_optimization_job;
