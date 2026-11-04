-- 接入 DeepSeek：记录结构化解析与匹配判定来源，便于审计与降级说明
ALTER TABLE data_bounty ADD COLUMN structured_by VARCHAR(16) NOT NULL DEFAULT 'rule';
ALTER TABLE data_bounty ADD COLUMN structured_model VARCHAR(64) NULL;
ALTER TABLE agent_scan_result ADD COLUMN judge_source VARCHAR(16) NOT NULL DEFAULT 'rule';
ALTER TABLE agent_scan_result ADD COLUMN judge_reason VARCHAR(512) NULL;
