-- ============================================================
-- 智能求职辅助平台 数据库初始化脚本
-- 生成日期：2026-10-20（V1.13，M21）
-- 来源：docs/数据库设计.md（V1.13）
-- 用法：mysql -u root -p < career_pilot_init.sql
-- ============================================================

CREATE DATABASE IF NOT EXISTS career_pilot
  CHARACTER SET utf8mb4
  COLLATE utf8mb4_0900_ai_ci;

USE career_pilot;


-- ============================================================
-- 1. 用户表
-- ============================================================
CREATE TABLE sys_user (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  username VARCHAR(50) NOT NULL,
  email VARCHAR(100) NOT NULL,
  password VARCHAR(255) NOT NULL COMMENT 'BCrypt 哈希，不存明文',
  nickname VARCHAR(50) DEFAULT NULL,
  avatar VARCHAR(500) DEFAULT NULL,
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1正常，0禁用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_sys_user_username (username),
  UNIQUE KEY uk_sys_user_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 2. 简历表
-- ============================================================
CREATE TABLE resume (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  name VARCHAR(100) NOT NULL,
  file_name VARCHAR(255) NOT NULL COMMENT '用户上传时的原始文件名',
  file_type VARCHAR(20) NOT NULL COMMENT 'PDF/DOCX',
  file_path VARCHAR(500) NOT NULL COMMENT '相对 storage-root 的路径，不向前端暴露',
  content LONGTEXT NOT NULL COMMENT '提取的简历文本（入库前按上限截断）',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1有效，0已删除（逻辑删除）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_resume_user_created (user_id, created_at),
  CONSTRAINT fk_resume_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历表';

-- ============================================================
-- 3. 简历分析结果
-- ============================================================
CREATE TABLE resume_analysis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  resume_id BIGINT NOT NULL,
  version_no INT NOT NULL DEFAULT 1 COMMENT '同一简历的分析版本号，从 1 递增',
  source_hash VARCHAR(64) DEFAULT NULL COMMENT '分析时简历文本的 SHA-256，数据追溯用',
  summary TEXT,
  skills JSON,
  education JSON,
  projects JSON,
  experience JSON,
  advantages JSON,
  weaknesses JSON,
  suggestions JSON,
  model_name VARCHAR(100) COMMENT '实际调用的模型名',
  status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  error_message VARCHAR(500) DEFAULT NULL COMMENT '仅排查用，不向前端返回',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_resume_analysis_version (resume_id, version_no),
  KEY idx_resume_analysis_hash (resume_id, source_hash),
  CONSTRAINT fk_resume_analysis_resume FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历分析结果';

-- ============================================================
-- 4. 目标岗位
-- ============================================================
CREATE TABLE job (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  company_name VARCHAR(100) DEFAULT NULL,
  job_name VARCHAR(100) NOT NULL,
  description LONGTEXT NOT NULL,
  apply_url VARCHAR(500) DEFAULT NULL COMMENT '投递网页链接（用户填写，http/https）',
  progress_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '求职进度（M21）：PENDING/APPLIED/WRITTEN/INTERVIEW_1/INTERVIEW_2/HR/OFFER/REJECTED',
  status TINYINT NOT NULL DEFAULT 1 COMMENT '1有效，0已删除（逻辑删除）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_job_user_created (user_id, created_at),
  CONSTRAINT fk_job_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='目标岗位';

-- ============================================================
-- 5. 岗位分析结果
-- ============================================================
CREATE TABLE job_analysis (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  job_id BIGINT NOT NULL,
  version_no INT NOT NULL DEFAULT 1 COMMENT '同一岗位的分析版本号，从 1 递增',
  source_hash VARCHAR(64) DEFAULT NULL COMMENT '分析时岗位描述的 SHA-256，用于判断分析是否过期（stale）',
  responsibilities JSON,
  required_skills JSON,
  preferred_skills JSON,
  education_requirement VARCHAR(255),
  experience_requirement VARCHAR(255),
  technology_stack JSON,
  soft_skills JSON,
  summary TEXT,
  model_name VARCHAR(100),
  status VARCHAR(20) NOT NULL DEFAULT 'SUCCESS' COMMENT 'SUCCESS/FAILED',
  error_message VARCHAR(500) DEFAULT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_job_analysis_version (job_id, version_no),
  KEY idx_job_analysis_hash (job_id, source_hash),
  CONSTRAINT fk_job_analysis_job FOREIGN KEY (job_id) REFERENCES job(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='岗位分析结果';

-- ============================================================
-- 6. 简历岗位匹配记录
-- ============================================================
CREATE TABLE match_record (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  resume_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  resume_analysis_id BIGINT NOT NULL COMMENT '本次匹配所依据的简历分析版本，业务上必然存在（见 5.8）',
  job_analysis_id BIGINT NOT NULL COMMENT '本次匹配所依据的岗位分析版本，业务上必然存在（见 5.8）',
  match_score DECIMAL(5,2) NOT NULL COMMENT '0.00至100.00，由后端规则计算',
  matched_items JSON,
  partial_items JSON,
  missing_items JSON,
  dimension_scores JSON,
  suggestions JSON,
  summary TEXT,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_match_user_created (user_id, created_at),
  KEY idx_match_resume_job (resume_id, job_id),
  KEY idx_match_job (job_id),
  KEY idx_match_resume_analysis (resume_analysis_id),
  KEY idx_match_job_analysis (job_analysis_id),
  CONSTRAINT fk_match_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_match_resume FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE RESTRICT,
  CONSTRAINT fk_match_job FOREIGN KEY (job_id) REFERENCES job(id) ON DELETE RESTRICT,
  CONSTRAINT fk_match_resume_analysis FOREIGN KEY (resume_analysis_id) REFERENCES resume_analysis(id) ON DELETE RESTRICT,
  CONSTRAINT fk_match_job_analysis FOREIGN KEY (job_analysis_id) REFERENCES job_analysis(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历岗位匹配记录';

-- ============================================================
-- 7. 面试会话
-- ============================================================
CREATE TABLE interview_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  resume_id BIGINT DEFAULT NULL COMMENT '关联简历（M12：面试出题参考的候选人简历，可空）',
  optimization_id BIGINT DEFAULT NULL COMMENT '关联优化记录（M12：从优化工作台发起时写入，可空）',
  title VARCHAR(100) NOT NULL,
  question_type VARCHAR(30) NOT NULL DEFAULT 'MIXED' COMMENT 'JAVA/SPRING/MYSQL/REDIS/PROJECT/MIXED',
  question_count INT NOT NULL DEFAULT 5 COMMENT '计划题目数，创建会话时确定',
  status TINYINT NOT NULL DEFAULT 0 COMMENT '0进行中，1已完成，2已取消',
  total_score DECIMAL(5,2) DEFAULT NULL COMMENT '全部作答完成后计算',
  overall_summary TEXT DEFAULT NULL COMMENT 'AI 总评，会话完成时写入（M7）',
  strengths JSON DEFAULT NULL COMMENT 'AI 归纳的强项标签（M7）',
  weaknesses JSON DEFAULT NULL COMMENT 'AI 归纳的薄弱标签（M7）',
  recommendations JSON DEFAULT NULL COMMENT 'AI 归纳的提升建议（M7）',
  llm_provider_key VARCHAR(50) DEFAULT NULL COMMENT '钉住的模型服务标识（M14：出题时快照，不建外键，见 5.13）',
  llm_model VARCHAR(100) DEFAULT NULL COMMENT '钉住的模型名（M14：出题时的实际模型快照）',
  summary_source VARCHAR(10) DEFAULT NULL COMMENT '总评来源：AI/DEGRADED；未生成时为 NULL（M15）',
  summary_generated_at DATETIME DEFAULT NULL COMMENT '总评生成时间（M15）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  completed_at DATETIME DEFAULT NULL,
  KEY idx_interview_user_created (user_id, created_at),
  KEY idx_interview_job (job_id),
  KEY idx_interview_resume (resume_id),
  KEY idx_interview_optimization (optimization_id),
  CONSTRAINT fk_interview_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_interview_job FOREIGN KEY (job_id) REFERENCES job(id) ON DELETE RESTRICT,
  CONSTRAINT fk_interview_resume FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE RESTRICT
  -- fk_interview_optimization（optimization_id）：因建表顺序依赖第 11 张表，移至文件末尾补充（V1.8 修复 errno 150）
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试会话';

-- V1.4 增量（既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE interview_session
--   ADD COLUMN overall_summary TEXT NULL COMMENT 'AI 总评，会话完成时写入（M7）',
--   ADD COLUMN strengths JSON NULL COMMENT 'AI 归纳的强项标签（M7）',
--   ADD COLUMN weaknesses JSON NULL COMMENT 'AI 归纳的薄弱标签（M7）',
--   ADD COLUMN recommendations JSON NULL COMMENT 'AI 归纳的提升建议（M7）';

-- V1.7 增量（M12，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE interview_session
--   ADD COLUMN resume_id BIGINT DEFAULT NULL COMMENT '关联简历（M12）',
--   ADD COLUMN optimization_id BIGINT DEFAULT NULL COMMENT '关联优化记录（M12）',
--   ADD KEY idx_interview_resume (resume_id),
--   ADD KEY idx_interview_optimization (optimization_id),
--   ADD CONSTRAINT fk_interview_resume FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE RESTRICT,
--   ADD CONSTRAINT fk_interview_optimization FOREIGN KEY (optimization_id) REFERENCES resume_optimization(id) ON DELETE RESTRICT;

-- V1.8 增量（M14，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE interview_session
--   ADD COLUMN llm_provider_key VARCHAR(50) DEFAULT NULL COMMENT '钉住的模型服务标识（M14）',
--   ADD COLUMN llm_model VARCHAR(100) DEFAULT NULL COMMENT '钉住的模型名（M14）';
-- 外键修复说明：interview_session.optimization_id 的外键在"全新库按本文件顺序执行"时依赖后建的第 11 张表
-- （旧顺序会在建 interview_session 时报 errno 150），现从本表 DDL 移出、改为在文件末尾所有表建立后补充。
-- 既有库在此前的 V1.7 增量中已成功建过该外键，无需任何处理。

-- V1.9 增量（M15，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE interview_session
--   ADD COLUMN summary_source VARCHAR(10) DEFAULT NULL COMMENT '总评来源：AI/DEGRADED（M15）',
--   ADD COLUMN summary_generated_at DATETIME DEFAULT NULL COMMENT '总评生成时间（M15）';

-- ============================================================
-- 8. 面试题与回答
-- ============================================================
CREATE TABLE interview_question (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  session_id BIGINT NOT NULL,
  question TEXT NOT NULL,
  question_category VARCHAR(50) DEFAULT NULL,
  answer TEXT COMMENT '用户回答，未作答为 NULL',
  reference_answer TEXT,
  score DECIMAL(5,2) DEFAULT NULL,
  analysis TEXT,
  suggestions JSON,
  topic_summary VARCHAR(200) DEFAULT NULL COMMENT '出题时生成的知识点摘要（M15：跨会话历史去重与展示）',
  sort_order INT NOT NULL DEFAULT 1 COMMENT '题目顺序，同一会话内唯一',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_interview_question_order (session_id, sort_order),
  CONSTRAINT fk_interview_question_session FOREIGN KEY (session_id) REFERENCES interview_session(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试题与回答';

-- V1.9 增量（M15，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE interview_question
--   ADD COLUMN topic_summary VARCHAR(200) DEFAULT NULL COMMENT '出题时生成的知识点摘要（M15）';

-- ============================================================
-- 9. 智能体会话
-- ============================================================
CREATE TABLE agent_session (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL COMMENT '应用层生成的会话标识（UUID）',
  title VARCHAR(100) NOT NULL COMMENT '会话标题，取首条用户消息的前若干字符',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '会话列表排序依据；写入新消息时须由应用层显式更新（见 5.9）',
  UNIQUE KEY uk_agent_session_sid (session_id),
  KEY idx_agent_session_user (user_id, updated_at),
  CONSTRAINT fk_agent_session_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体会话';

-- ============================================================
-- 10. 智能体对话与工具调用记录
-- ============================================================
CREATE TABLE agent_chat_message (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  session_id VARCHAR(64) NOT NULL COMMENT '关联 agent_session.session_id',
  role VARCHAR(20) NOT NULL COMMENT 'user/assistant/tool',
  content LONGTEXT,
  tool_name VARCHAR(100) DEFAULT NULL COMMENT 'role=tool 时记录工具名',
  tool_arguments JSON DEFAULT NULL COMMENT 'role=tool 时记录入参',
  tool_result JSON DEFAULT NULL COMMENT 'role=tool 时记录（脱敏后的）结果摘要',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  KEY idx_agent_session_created (user_id, session_id, created_at),
  KEY idx_agent_message_session (session_id),
  CONSTRAINT fk_agent_message_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_agent_message_session FOREIGN KEY (session_id) REFERENCES agent_session(session_id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='智能体对话与工具调用记录';

-- ============================================================
-- 11. 简历定向优化记录（V1.6 新增，M11）
-- ============================================================
CREATE TABLE resume_optimization (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  resume_id BIGINT NOT NULL,
  job_id BIGINT NOT NULL,
  match_record_id BIGINT DEFAULT NULL COMMENT '优化前匹配评估（复用 match_record；M16 起可空——任务完成前尚未产生匹配）',
  version_no INT NOT NULL DEFAULT 1 COMMENT '同一简历+岗位的优化版本号，从 1 递增',
  optimized_content LONGTEXT COMMENT '模型输出的优化后简历（Markdown 原稿，不可变，见《数据库设计》5.11）',
  changes JSON COMMENT '逐项修改说明 [{section,type,reason,before,after}]',
  gap_questions JSON COMMENT '待用户确认的问题（简历中缺失的事实，不由模型补全）',
  edited_content LONGTEXT DEFAULT NULL COMMENT '用户编辑后的当前稿（不覆盖 optimized_content）',
  edit_history JSON DEFAULT NULL COMMENT '编辑历史快照 [{content,savedAt}]，最多保留 10 版',
  score_after DECIMAL(5,2) DEFAULT NULL COMMENT '优化后匹配总分（重新评估后写入，0.00–100.00）',
  dimension_scores_after JSON DEFAULT NULL COMMENT '优化后维度得分（结构同 match_record.dimension_scores）',
  optimized_analysis JSON DEFAULT NULL COMMENT '优化稿的结构化分析（评估依据，追溯用）',
  evaluated_at DATETIME DEFAULT NULL COMMENT '最近一次评估时间',
  model_name VARCHAR(100) COMMENT '实际调用的模型名（M14 起写入钉住 provider 的真实模型名）',
  llm_provider_key VARCHAR(50) DEFAULT NULL COMMENT '钉住的模型服务标识（M14：创建时快照，不建外键，见 5.13）',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态机：PENDING/PROCESSING/SUCCESS/FAILED（M16 扩展）',
  error_message VARCHAR(500) DEFAULT NULL COMMENT '失败原因（M16 起为用户可读文案，随详情返回）',
  failure_detail LONGTEXT DEFAULT NULL COMMENT '事实校验失败的全部问题（JSON 数组，脱敏；V1.13 新增，成功时清空）',
  attempt_id VARCHAR(36) DEFAULT NULL COMMENT '执行代次围栏（M16）',
  stage VARCHAR(20) DEFAULT NULL COMMENT '当前阶段：MATCHING/GENERATING（M16，PROCESSING 时有值）',
  started_at DATETIME DEFAULT NULL COMMENT '开始执行时间（M16）',
  finished_at DATETIME DEFAULT NULL COMMENT '终态时间（M16）',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'M16 起为「最后一次有效进展时间」：状态推进与心跳都显式推进',
  UNIQUE KEY uk_resume_optimization_version (resume_id, job_id, version_no),
  KEY idx_resume_optimization_user_created (user_id, created_at),
  KEY idx_resume_optimization_job (job_id),
  KEY idx_resume_optimization_match (match_record_id),
  KEY idx_resume_optimization_status_updated (status, updated_at),
  CONSTRAINT fk_resume_optimization_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT,
  CONSTRAINT fk_resume_optimization_resume FOREIGN KEY (resume_id) REFERENCES resume(id) ON DELETE RESTRICT,
  CONSTRAINT fk_resume_optimization_job FOREIGN KEY (job_id) REFERENCES job(id) ON DELETE RESTRICT,
  CONSTRAINT fk_resume_optimization_match FOREIGN KEY (match_record_id) REFERENCES match_record(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='简历定向优化记录';

-- V1.6 增量（既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- 该表为 V1.6 全新新增、无列级变更，把上面的 CREATE TABLE resume_optimization 整段执行一次即可。

-- V1.8 增量（M14，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE resume_optimization
--   ADD COLUMN llm_provider_key VARCHAR(50) DEFAULT NULL COMMENT '钉住的模型服务标识（M14）';

-- V1.10 增量（M16，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- ALTER TABLE resume_optimization
--   MODIFY COLUMN status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT '任务状态机：PENDING/PROCESSING/SUCCESS/FAILED（M16 扩展）',
--   MODIFY COLUMN match_record_id BIGINT DEFAULT NULL COMMENT '优化前匹配评估（M16 起可空）',
--   ADD COLUMN attempt_id VARCHAR(36) DEFAULT NULL COMMENT '执行代次围栏（M16）',
--   ADD COLUMN stage VARCHAR(20) DEFAULT NULL COMMENT '当前阶段：MATCHING/GENERATING（M16）',
--   ADD COLUMN started_at DATETIME DEFAULT NULL COMMENT '开始执行时间（M16）',
--   ADD COLUMN finished_at DATETIME DEFAULT NULL COMMENT '终态时间（M16）',
--   ADD KEY idx_resume_optimization_status_updated (status, updated_at);

-- V1.13 增量（2026-10-21，既有库升级执行；新建库执行上面的完整 DDL 即可）：
-- 事实校验失败的完整问题清单（error_message 只有 500 字符，仅放汇总）。
-- ALTER TABLE resume_optimization
--   ADD COLUMN failure_detail LONGTEXT DEFAULT NULL COMMENT '事实校验失败的全部问题（JSON 数组，脱敏；V1.13 新增，成功时清空）';

-- ============================================================
-- 12. 模型服务配置（V1.8 新增，M14）
-- ============================================================
CREATE TABLE llm_provider (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  provider_key VARCHAR(50) NOT NULL COMMENT '用户内唯一的服务标识（deepseek/kimi/glm/custom-N），钉入会话与优化的快照列',
  display_name VARCHAR(100) NOT NULL COMMENT '展示名',
  kind VARCHAR(20) NOT NULL COMMENT '构建方式：DEEPSEEK（原生 DeepSeekChatModel）/OPENAI（OpenAI 兼容）',
  base_url VARCHAR(500) NOT NULL,
  model VARCHAR(100) NOT NULL,
  temperature DECIMAL(3,2) NOT NULL DEFAULT 0.30,
  api_key_ciphertext VARCHAR(1000) DEFAULT NULL COMMENT 'API Key 密文（AES-GCM，Base64），不存明文',
  api_key_nonce VARCHAR(64) DEFAULT NULL COMMENT 'AES-GCM 随机 nonce（12 字节，Base64）',
  is_default TINYINT NOT NULL DEFAULT 0 COMMENT '1 为当前用户默认服务；每用户至多一个（服务层保证）',
  builtin TINYINT NOT NULL DEFAULT 0 COMMENT '1 为内置服务（不可删除）',
  enabled TINYINT NOT NULL DEFAULT 1 COMMENT '1 启用，0 停用',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  UNIQUE KEY uk_llm_provider_user_key (user_id, provider_key),
  CONSTRAINT fk_llm_provider_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型服务配置';

-- V1.8 说明：本表为 V1.8 全新新增，既有库执行上面的 CREATE TABLE 整段即可。

-- ============================================================
-- 13. 面试日程（V1.11 新增，M17）
-- ============================================================
CREATE TABLE interview_schedule (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  company_name VARCHAR(100) NOT NULL COMMENT '公司名（解析或手工填写）',
  position VARCHAR(100) NOT NULL COMMENT '岗位名',
  interview_time DATETIME NOT NULL COMMENT '面试时间',
  interview_type VARCHAR(20) NOT NULL DEFAULT 'VIDEO' COMMENT 'ONSITE/VIDEO/PHONE',
  meeting_link VARCHAR(500) DEFAULT NULL COMMENT '会议链接或会议号信息（解析得到）',
  round_number TINYINT NOT NULL DEFAULT 1 COMMENT '轮次（一面/二面…；中文数字解析归一）',
  interviewer VARCHAR(50) DEFAULT NULL COMMENT '面试官（可空）',
  notes VARCHAR(1000) DEFAULT NULL COMMENT '备注',
  status VARCHAR(20) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/COMPLETED/CANCELLED/EXPIRED（EXPIRED 仅系统写入）',
  parse_source VARCHAR(10) NOT NULL DEFAULT 'MANUAL' COMMENT '来源：RULE/AI/MANUAL',
  parse_confidence DECIMAL(3,2) DEFAULT NULL COMMENT '规则 0.95 / AI 0.80 / 手工 NULL',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  KEY idx_schedule_user_time (user_id, interview_time),
  KEY idx_schedule_status_time (status, interview_time),
  CONSTRAINT fk_schedule_user FOREIGN KEY (user_id) REFERENCES sys_user(id) ON DELETE RESTRICT
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='面试日程';

-- V1.11 说明：本表为 V1.11 全新新增，既有库执行上面的 CREATE TABLE 整段即可。

-- ============================================================
-- 外键补充（V1.8：修复全新库建表顺序）
-- ============================================================
-- interview_session.optimization_id 引用第 11 张表 resume_optimization：
-- 全新库按本文件顺序执行时，interview_session（第 7 张）先于被引用表创建，
-- 若把该外键内联在 interview_session 的 CREATE 中会报 errno 150（Foreign key constraint is incorrectly formed）。
-- 故统一移到此处在所有表建立后补充。
ALTER TABLE interview_session
  ADD CONSTRAINT fk_interview_optimization FOREIGN KEY (optimization_id) REFERENCES resume_optimization(id) ON DELETE RESTRICT;
