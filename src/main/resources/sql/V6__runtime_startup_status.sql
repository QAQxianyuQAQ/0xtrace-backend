-- 启动阶段先持久化队伍实例；镜像拉取完成前尚未确定容器监听端口。
ALTER TABLE challenge_container_instances
    MODIFY COLUMN container_port INT NULL COMMENT '容器内部监听端口';
