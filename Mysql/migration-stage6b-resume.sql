-- ============================================================
-- 阶段 6b：career-resume 独立的数据迁移
-- 日期：2026-11-04
-- 1. 新建 cp_resume，复制 resume / resume_analysis；
-- 2. 删除 career_pilot 中对 resume / resume_analysis 的跨库外键；
-- 3. career_pilot.resume / resume_analysis 保留只读作为回滚点。
-- 仅需执行一次；外键重复删除会报 1091，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_resume
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_resume.resume LIKE career_pilot.resume;
INSERT IGNORE INTO cp_resume.resume SELECT * FROM career_pilot.resume;

CREATE TABLE IF NOT EXISTS cp_resume.resume_analysis LIKE career_pilot.resume_analysis;
INSERT IGNORE INTO cp_resume.resume_analysis SELECT * FROM career_pilot.resume_analysis;

ALTER TABLE career_pilot.match_record    DROP FOREIGN KEY fk_match_resume;
ALTER TABLE career_pilot.match_record    DROP FOREIGN KEY fk_match_resume_analysis;
ALTER TABLE career_pilot.interview_session DROP FOREIGN KEY fk_interview_resume;
ALTER TABLE career_pilot.resume_optimization DROP FOREIGN KEY fk_resume_optimization_resume;
