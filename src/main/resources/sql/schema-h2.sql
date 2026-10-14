-- TraceQuest 建表脚本（H2 兼容版本，仅用于本地与自动化测试）
-- 与 V1__init_schema.sql 结构一致，仅调整与 MySQL 不兼容的语法与类型。

CREATE TABLE IF NOT EXISTS users (
    id            VARCHAR(40)  NOT NULL,
    username      VARCHAR(64)  NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16)  NOT NULL,
    status        VARCHAR(16)  NOT NULL,
    team_id       VARCHAR(40)  NULL,
    note          VARCHAR(512) NULL,
    nickname      VARCHAR(32)  NULL,
    avatar_url    VARCHAR(512) NULL,
    token_version INT          NOT NULL DEFAULT 0,
    created_at    TIMESTAMP(3) NULL,
    last_login_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_users_username ON users (username);
CREATE INDEX IF NOT EXISTS idx_users_role_status ON users (role, status);

CREATE TABLE IF NOT EXISTS teams (
    id         VARCHAR(40)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    note       VARCHAR(512) NULL,
    created_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_teams_name ON teams (name);

CREATE TABLE IF NOT EXISTS team_members (
    id        VARCHAR(40) NOT NULL,
    team_id   VARCHAR(40) NOT NULL,
    user_id   VARCHAR(40) NOT NULL,
    joined_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_team_members_user ON team_members (user_id);

CREATE TABLE IF NOT EXISTS events (
    id         VARCHAR(40)  NOT NULL,
    name       VARCHAR(128) NOT NULL,
    status     VARCHAR(16)  NOT NULL,
    timezone   VARCHAR(64)  NOT NULL,
    starts_at  TIMESTAMP(3) NULL,
    ends_at    TIMESTAMP(3) NULL,
    created_at TIMESTAMP(3) NULL,
    updated_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS challenges (
    id            VARCHAR(40)  NOT NULL,
    event_id      VARCHAR(40)  NULL,
    code          VARCHAR(24)  NOT NULL,
    title         VARCHAR(128) NOT NULL,
    category      VARCHAR(32)  NOT NULL,
    difficulty    VARCHAR(32)  NOT NULL,
    points        INT          NOT NULL,
    bonus_first   INT          NOT NULL DEFAULT 0,
    bonus_second  INT          NOT NULL DEFAULT 0,
    bonus_third   INT          NOT NULL DEFAULT 0,
    bonus_other   INT          NOT NULL DEFAULT 0,
    description   VARCHAR(8000) NULL,
    answer_secret VARCHAR(512) NULL,
    cover_file_id VARCHAR(40)  NULL,
    cover_url     VARCHAR(512) NULL,
    status        VARCHAR(16)  NOT NULL,
    version       INT          NOT NULL DEFAULT 1,
    published_at  TIMESTAMP(3) NULL,
    created_at    TIMESTAMP(3) NULL,
    updated_at    TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_challenges_code ON challenges (code);

CREATE TABLE IF NOT EXISTS challenge_files (
    id            VARCHAR(40)  NOT NULL,
    challenge_id  VARCHAR(40)  NOT NULL,
    kind          VARCHAR(16)  NOT NULL,
    storage_key   VARCHAR(256) NOT NULL,
    original_name VARCHAR(256) NOT NULL,
    size_bytes    BIGINT       NOT NULL DEFAULT 0,
    content_type  VARCHAR(128) NULL,
    created_by    VARCHAR(40)  NULL,
    created_at    TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS submissions (
    id             VARCHAR(40) NOT NULL,
    user_id        VARCHAR(40) NOT NULL,
    team_id        VARCHAR(40) NULL,
    challenge_id   VARCHAR(40) NOT NULL,
    answer_digest  VARCHAR(64) NOT NULL,
    result         VARCHAR(16) NOT NULL,
    points_awarded INT         NOT NULL DEFAULT 0,
    solve_place    INT         NULL,
    source         VARCHAR(16) NOT NULL,
    request_id     VARCHAR(64) NULL,
    submitted_at   TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS solves (
    id              VARCHAR(40) NOT NULL,
    user_id         VARCHAR(40) NOT NULL,
    team_id         VARCHAR(40) NULL,
    subject_key     VARCHAR(40) NOT NULL,
    challenge_id    VARCHAR(40) NOT NULL,
    submission_id   VARCHAR(40) NULL,
    solved_at       TIMESTAMP(3) NULL,
    solve_place     INT         NOT NULL,
    base_points     INT         NOT NULL DEFAULT 0,
    bonus_points    INT         NOT NULL DEFAULT 0,
    source          VARCHAR(16) NOT NULL,
    idempotency_key VARCHAR(64) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_solves_subject_challenge ON solves (subject_key, challenge_id);

CREATE TABLE IF NOT EXISTS location_tokens (
    id           VARCHAR(40) NOT NULL,
    challenge_id VARCHAR(40) NOT NULL,
    token_hash   VARCHAR(64) NOT NULL,
    generation   INT         NOT NULL DEFAULT 1,
    expires_at   TIMESTAMP(3) NULL,
    revoked_at   TIMESTAMP(3) NULL,
    created_by   VARCHAR(40) NULL,
    created_at   TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_location_tokens_hash ON location_tokens (token_hash);

CREATE TABLE IF NOT EXISTS score_adjustments (
    id         VARCHAR(40)  NOT NULL,
    team_id    VARCHAR(40)  NULL,
    user_id    VARCHAR(40)  NULL,
    delta      INT          NOT NULL,
    reason     VARCHAR(512) NOT NULL,
    admin_id   VARCHAR(40)  NULL,
    created_at TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS audit_logs (
    id          VARCHAR(40)  NOT NULL,
    actor_id    VARCHAR(40)  NULL,
    actor_name  VARCHAR(64)  NULL,
    action      VARCHAR(64)  NOT NULL,
    entity_type VARCHAR(32)  NULL,
    entity_id   VARCHAR(40)  NULL,
    before_json VARCHAR(8000) NULL,
    after_json  VARCHAR(8000) NULL,
    request_id  VARCHAR(64)  NULL,
    client_ip   VARCHAR(64)  NULL,
    created_at  TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS idempotency_records (
    id              VARCHAR(40)  NOT NULL,
    scope           VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    user_id         VARCHAR(40)  NOT NULL DEFAULT '',
    request_hash    VARCHAR(64)  NULL,
    response_json   VARCHAR(8000) NULL,
    created_at      TIMESTAMP(3) NULL,
    PRIMARY KEY (id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_idempotency_scope_key_user
    ON idempotency_records (scope, idempotency_key, user_id);
