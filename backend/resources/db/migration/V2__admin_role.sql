-- 管理员后台：文档表格之外的新增字段，0=普通用户 1=管理员
ALTER TABLE "user" ADD COLUMN role SMALLINT NOT NULL DEFAULT 0;
CREATE INDEX idx_user_file_recycle ON user_file (user_id, is_recycle);
