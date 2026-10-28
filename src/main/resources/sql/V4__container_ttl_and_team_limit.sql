-- 队伍单容器、随机端口与一小时生命周期迁移（MySQL 8.x）
-- 说明：容器监听端口由镜像 EXPOSE 自动解析，管理端不再填写。

ALTER TABLE challenges
    DROP COLUMN container_port;

ALTER TABLE challenge_container_instances
    ADD COLUMN expires_at DATETIME(3) NULL COMMENT '容器到期时间' AFTER ready_at,
    DROP INDEX uk_container_instance_challenge_team,
    ADD UNIQUE KEY uk_container_instance_team (team_id),
    ADD KEY idx_container_instance_expires (status, expires_at);
