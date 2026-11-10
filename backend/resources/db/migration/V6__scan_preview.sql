-- 扫描结果预览：解析出的正文/表格片段，供前端在扫描完成后直接看内容
ALTER TABLE agent_scan_result ADD COLUMN preview_text TEXT NULL;
ALTER TABLE agent_scan_result ADD COLUMN preview_kind VARCHAR(16) NULL;
