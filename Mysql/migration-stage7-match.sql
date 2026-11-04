-- ============================================================
-- 阶段 7：career-match 独立的数据迁移
-- 日期：2026-11-04
-- 1. 新建 cp_match，复制 match_record / resume_optimization；
-- 2. 新增 match_record.resume_version_no / job_version_no（冗余版本号）并按分析表回填；
-- 3. 新增 resume_optimization.input_snapshot（任务输入快照，旧数据为 NULL）；
-- 4. career_pilot.match_record / resume_optimization 保留只读作为回滚点。
--
-- 跨库外键已在阶段 4（user）、阶段 6（job）、阶段 6b（resume）删除，
-- 本阶段无需再删除外键；CREATE TABLE ... LIKE 不复制外键定义。
-- 仅需执行一次；外键重复删除会报 1091，可忽略。
-- ============================================================

CREATE DATABASE IF NOT EXISTS cp_match
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS cp_match.match_record LIKE career_pilot.match_record;
INSERT IGNORE INTO cp_match.match_record SELECT * FROM career_pilot.match_record;

ALTER TABLE cp_match.match_record
  ADD COLUMN resume_version_no INT NULL AFTER job_analysis_id,
  ADD COLUMN job_version_no INT NULL AFTER resume_version_no;

-- 回填冗余版本号（旧数据展示时不再回查简历/岗位服务）
UPDATE cp_match.match_record m
  JOIN cp_resume.resume_analysis ra ON m.resume_analysis_id = ra.id
  SET m.resume_version_no = ra.version_no;
UPDATE cp_match.match_record m
  JOIN cp_job.job_analysis ja ON m.job_analysis_id = ja.id
  SET m.job_version_no = ja.version_no;

CREATE TABLE IF NOT EXISTS cp_match.resume_optimization LIKE career_pilot.resume_optimization;
INSERT IGNORE INTO cp_match.resume_optimization SELECT * FROM career_pilot.resume_optimization;

ALTER TABLE cp_match.resume_optimization
  ADD COLUMN input_snapshot JSON NULL AFTER llm_provider_key;

-- 优化记录迁出后，interview_session 对它的引用变为跨库外键（面试服务尚未拆分，
-- 但外键约束必须先删，否则引用 cp_match 优化记录的面试无法落库）。
ALTER TABLE career_pilot.interview_session DROP FOREIGN KEY fk_interview_optimization;

-- 回滚兼容：单体 app.match.mode=local 时仍写 career_pilot，新实体列需同步存在。
ALTER TABLE career_pilot.match_record
  ADD COLUMN resume_version_no INT NULL AFTER job_analysis_id,
  ADD COLUMN job_version_no INT NULL AFTER resume_version_no;
ALTER TABLE career_pilot.resume_optimization
  ADD COLUMN input_snapshot JSON NULL AFTER llm_provider_key;
