-- TraceQuest 竞赛平台初始化建表脚本（MySQL 8.x）
-- 说明：所有表使用 utf8mb4 字符集；时间列统一存放 UTC 时间。

-- 账号表：管理员与选手共用，通过 role 区分
CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(40)  NOT NULL COMMENT '业务主键，带前缀随机 ID',
    username      VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 密码哈希',
    role          VARCHAR(16)  NOT NULL COMMENT 'ADMIN/CONTESTANT',
    status        VARCHAR(16)  NOT NULL COMMENT 'ACTIVE/DISABLED',
    team_id       VARCHAR(40)  NULL COMMENT '所属队伍',
    note          VARCHAR(512) NULL COMMENT '后台备注',
    nickname      VARCHAR(32)  NULL COMMENT '昵称',
    avatar_url    VARCHAR(512) NULL COMMENT '头像地址',
    token_version INT          NOT NULL DEFAULT 0 COMMENT '会话版本号，用于令牌失效',
    created_at    DATETIME(3)  NULL,
    last_login_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    KEY idx_users_role_status (role, status),
    KEY idx_users_team (team_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '账号表';

-- 队伍表
CREATE TABLE IF NOT EXISTS teams (
    id         VARCHAR(40) NOT NULL,
    name       VARCHAR(128) NOT NULL,
    note       VARCHAR(512) NULL,
    created_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_teams_name (name)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '队伍表';

-- 队伍成员关系
CREATE TABLE IF NOT EXISTS team_members (
    id        VARCHAR(40) NOT NULL,
    team_id   VARCHAR(40) NOT NULL,
    user_id   VARCHAR(40) NOT NULL,
    joined_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_team_members_user (user_id),
    KEY idx_team_members_team (team_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '队伍成员表';

-- 赛事配置表（当前赛事通常只有一行）
CREATE TABLE IF NOT EXISTS events (
    id         VARCHAR(40)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL COMMENT 'SETUP/OPEN/PAUSED/CLOSED',
    timezone   VARCHAR(64)  NOT NULL COMMENT 'IANA 时区标识',
    starts_at  DATETIME(3)  NULL,
    ends_at    DATETIME(3)  NULL,
    created_at DATETIME(3)  NULL,
    updated_at DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_events_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '赛事配置表';

-- 题目表：answer_secret 存放 AES-GCM 密文，禁止明文
CREATE TABLE IF NOT EXISTS challenges (
    id             VARCHAR(40)  NOT NULL,
    event_id       VARCHAR(40)  NULL,
    code           VARCHAR(24)  NOT NULL COMMENT '题目编号，2-24 位大写英数及 _ -',
    title          VARCHAR(128) NOT NULL,
    category       VARCHAR(32)  NOT NULL,
    difficulty     VARCHAR(32)  NOT NULL,
    points         INT          NOT NULL,
    bonus_first    INT          NOT NULL DEFAULT 0,
    bonus_second   INT          NOT NULL DEFAULT 0,
    bonus_third    INT          NOT NULL DEFAULT 0,
    bonus_other    INT          NOT NULL DEFAULT 0,
    description    TEXT         NULL,
    answer_secret  VARCHAR(512) NULL COMMENT '答案密文（AES-GCM）',
    cover_file_id  VARCHAR(40)  NULL,
    cover_url      VARCHAR(512) NULL,
    status         VARCHAR(16)  NOT NULL COMMENT 'DRAFT/PUBLISHED/PAUSED/ARCHIVED',
    version        INT          NOT NULL DEFAULT 1 COMMENT '乐观锁版本号',
    published_at   DATETIME(3)  NULL,
    created_at     DATETIME(3)  NULL,
    updated_at     DATETIME(3)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_challenges_code (code),
    KEY idx_challenges_status (status),
    KEY idx_challenges_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目表';

-- 题目文件表：二进制内容存 MinIO，此处只存元数据
CREATE TABLE IF NOT EXISTS challenge_files (
    id            VARCHAR(40)  NOT NULL,
    challenge_id  VARCHAR(40)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL COMMENT 'ATTACHMENT/COVER',
    storage_key   VARCHAR(256) NOT NULL COMMENT 'MinIO 对象键',
    original_name VARCHAR(256) NOT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    content_type  VARCHAR(128) NULL,
    created_by    VARCHAR(40)  NULL,
    created_at    DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_challenge_files_challenge (challenge_id, kind)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '题目文件元数据表';

-- 提交记录表
CREATE TABLE IF NOT EXISTS submissions (
    id             VARCHAR(40) NOT NULL,
    user_id        VARCHAR(40) NOT NULL,
    team_id        VARCHAR(40) NULL,
    challenge_id   VARCHAR(40) NOT NULL,
    answer_digest  VARCHAR(64) NOT NULL COMMENT '提交内容 SHA-256 摘要',
    result         VARCHAR(16) NOT NULL COMMENT 'CORRECT/INCORRECT',
    points_awarded INT         NOT NULL DEFAULT 0,
    solve_place    INT         NULL,
    source         VARCHAR(16) NOT NULL COMMENT 'ANSWER/LOCATION',
    request_id     VARCHAR(64) NULL,
    submitted_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_submissions_user (user_id, submitted_at),
    KEY idx_submissions_team (team_id, submitted_at),
    KEY idx_submissions_challenge (challenge_id),
    KEY idx_submissions_request (request_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '提交记录表';

-- 解题记录表：subject_key 为队伍或个人的统计主体键
CREATE TABLE IF NOT EXISTS solves (
    id              VARCHAR(40) NOT NULL,
    user_id         VARCHAR(40) NOT NULL,
    team_id         VARCHAR(40) NULL,
    subject_key     VARCHAR(40) NOT NULL COMMENT '队伍 ID 或用户 ID',
    challenge_id    VARCHAR(40) NOT NULL,
    submission_id   VARCHAR(40) NULL,
    solved_at       DATETIME(3) NULL,
    solve_place     INT         NOT NULL,
    base_points     INT         NOT NULL DEFAULT 0,
    bonus_points    INT         NOT NULL DEFAULT 0,
    source          VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_solves_subject_challenge (subject_key, challenge_id),
    KEY idx_solves_challenge (challenge_id),
    KEY idx_solves_solved_at (solved_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '解题记录表';

-- 位置凭证表：仅存令牌哈希
CREATE TABLE IF NOT EXISTS location_tokens (
    id           VARCHAR(40) NOT NULL,
    challenge_id VARCHAR(40) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    generation   INT         NOT NULL DEFAULT 1,
    expires_at   DATETIME(3) NULL,
    revoked_at   DATETIME(3) NULL,
    created_by   VARCHAR(40) NULL,
    created_at   DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_location_tokens_hash (token_hash),
    KEY idx_location_tokens_challenge (challenge_id, revoked_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '现场位置凭证表';

-- 手工积分调整表
CREATE TABLE IF NOT EXISTS score_adjustments (
    id         VARCHAR(40) NOT NULL,
    team_id    VARCHAR(40) NULL,
    user_id    VARCHAR(40) NULL,
    delta      INT         NOT NULL,
    reason     VARCHAR(512) NOT NULL,
    admin_id   VARCHAR(40) NULL,
    created_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY idx_score_adjustments_team (team_id),
    KEY idx_score_adjustments_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '积分调整表';

-- 审计日志表
CREATE TABLE IF NOT EXISTS audit_logs (
    id          VARCHAR(40)  NOT NULL,
    actor_id    VARCHAR(40)  NULL,
    actor_name  VARCHAR(64)  NULL,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NULL,
    entity_id   VARCHAR(40)  NULL,
    before_json TEXT         NULL,
    after_json  TEXT         NULL,
    request_id  VARCHAR(64)  NULL,
    client_ip   VARCHAR(64)  NULL,
    created_at  DATETIME(3)  NULL,
    PRIMARY KEY (id),
    KEY idx_audit_logs_action (action),
    KEY idx_audit_logs_entity (entity_type, entity_id),
    KEY idx_audit_logs_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '后台操作审计表';

-- 幂等记录表
CREATE TABLE IF NOT EXISTS idempotency_records (
    id              VARCHAR(40) NOT NULL,
    scope           VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         VARCHAR(40) NOT NULL DEFAULT '',
    request_hash    VARCHAR(64) NULL,
    response_json   TEXT        NULL,
    created_at      DATETIME(3) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_scope_key_user (scope, idempotency_key, user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '写请求幂等记录表';

-- 精确对象清理意图；失败保留任务，不执行全桶删除。
CREATE TABLE IF NOT EXISTS storage_cleanup_jobs (
 storage_key VARCHAR(512) NOT NULL PRIMARY KEY,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上传回滚及封面替换清理任务';
