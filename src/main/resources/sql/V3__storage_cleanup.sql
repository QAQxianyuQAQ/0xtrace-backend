-- 精确对象清理意图；失败保留任务，不执行全桶删除。
CREATE TABLE IF NOT EXISTS storage_cleanup_jobs (
 storage_key VARCHAR(512) NOT NULL PRIMARY KEY,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传回滚及封面替换清理任务';
