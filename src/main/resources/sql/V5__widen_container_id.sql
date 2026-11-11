-- 修复 Docker 告警与容器标识混合输出后的兼容空间，保留完整容器标识。
ALTER TABLE challenge_container_instances
    MODIFY COLUMN container_id VARCHAR(128) NULL COMMENT 'Docker 容器完整标识';
