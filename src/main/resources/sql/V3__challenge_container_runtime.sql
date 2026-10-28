-- 题目 Docker 运行时与队伍隔离 Flag 迁移（MySQL 8.x）
-- 说明：单机 Docker 部署下，同一赛事队伍针对同一题目只保留一个容器实例。

-- 历史题目尽量归入最早创建的赛事，随后将赛事归属改为必填
UPDATE challenges
SET event_id = (SELECT id FROM events ORDER BY created_at ASC, id ASC LIMIT 1)
WHERE event_id IS NULL;

ALTER TABLE challenges
    MODIFY COLUMN event_id VARCHAR(40) NOT NULL COMMENT '题目独占所属赛事',
    ADD COLUMN runtime_type VARCHAR(16) NOT NULL DEFAULT 'MANUAL' COMMENT 'MANUAL/DOCKER' AFTER answer_secret,
    ADD COLUMN docker_image VARCHAR(255) NULL COMMENT 'Docker Hub 镜像仓库' AFTER runtime_type,
    ADD COLUMN docker_tag VARCHAR(128) NULL COMMENT 'Docker 镜像标签' AFTER docker_image,
    ADD COLUMN flag_env_name VARCHAR(128) NULL COMMENT '启动容器时注入 Flag 的环境变量名' AFTER docker_tag,
    ADD COLUMN flag_prefix VARCHAR(32) NULL COMMENT 'Flag 头，例如 sacc' AFTER flag_env_name,
    ADD COLUMN container_port INT NULL COMMENT '容器内部监听端口' AFTER flag_prefix,
    ADD KEY idx_challenges_event_status (event_id, status);

CREATE TABLE IF NOT EXISTS challenge_container_instances (
    id              VARCHAR(40)  NOT NULL,
    event_id        VARCHAR(40)  NOT NULL,
    challenge_id    VARCHAR(40)  NOT NULL,
    team_id         VARCHAR(40)  NOT NULL,
    container_id    VARCHAR(80)  NULL,
    container_name  VARCHAR(128) NOT NULL,
    status          VARCHAR(16)  NOT NULL COMMENT 'PENDING/PULLING/STARTING/READY/FAILED/STOPPED',
    docker_image    VARCHAR(255) NOT NULL,
    docker_tag      VARCHAR(128) NOT NULL,
    flag_env_name   VARCHAR(128) NOT NULL,
    flag_secret     VARCHAR(512) NOT NULL COMMENT '本队实际 Flag 的 AES-GCM 密文',
    container_ip    VARCHAR(64)  NULL,
    host_ip         VARCHAR(64)  NULL,
    host_port       INT          NULL,
    container_port  INT          NOT NULL,
    last_error      VARCHAR(512) NULL,
    ready_at        DATETIME(3)  NULL,
    created_at      DATETIME(3)  NULL,
    updated_at      DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_container_instance_challenge_team (challenge_id, team_id),
    KEY idx_container_instance_event_team (event_id, team_id),
    KEY idx_container_instance_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '队伍题目容器实例表';
