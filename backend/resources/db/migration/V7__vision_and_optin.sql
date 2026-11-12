-- 用户开关 + 视觉判定结果 + 降级标记
ALTER TABLE agent_scan_task ADD COLUMN ai_enabled SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE agent_scan_result ADD COLUMN vision_scene TEXT NULL;
ALTER TABLE agent_scan_result ADD COLUMN vision_ok SMALLINT NULL;
ALTER TABLE agent_scan_result ADD COLUMN level_capped SMALLINT NOT NULL DEFAULT 0;
