-- 赛事内队伍与队伍人数上限迁移（MySQL 8.x）
-- 说明：队伍关系从账号全局归属调整为赛事内唯一归属，提交和排名统一使用赛事队伍 ID。

ALTER TABLE events
    ADD COLUMN max_team_size INT NOT NULL DEFAULT 4 COMMENT '赛事队伍人数上限' AFTER ends_at;

ALTER TABLE teams
    ADD COLUMN event_id VARCHAR(40) NULL COMMENT '所属赛事' AFTER id,
    ADD COLUMN join_code VARCHAR(32) NULL COMMENT '队伍唯一加入标识码' AFTER name,
    ADD COLUMN created_by VARCHAR(40) NULL COMMENT '创建队伍账号' AFTER join_code;

-- 历史数据尽量归入最早创建的赛事；无赛事时保留空值供管理员后续治理。
UPDATE teams
SET event_id = (SELECT id FROM events ORDER BY created_at ASC, id ASC LIMIT 1)
WHERE event_id IS NULL;

-- 历史队伍没有邀请码时生成稳定且唯一的占位码。
UPDATE teams
SET join_code = CONCAT('LEGACY-', UPPER(SUBSTRING(SHA2(id, 256), 1, 16)))
WHERE join_code IS NULL;

ALTER TABLE teams
    DROP INDEX uk_teams_name,
    ADD UNIQUE KEY uk_teams_event_name (event_id, name),
    ADD UNIQUE KEY uk_teams_join_code (join_code),
    ADD KEY idx_teams_event (event_id);

ALTER TABLE team_members
    ADD COLUMN event_id VARCHAR(40) NULL COMMENT '成员关系所属赛事' AFTER id;

UPDATE team_members tm
JOIN teams t ON t.id = tm.team_id
SET tm.event_id = t.event_id
WHERE tm.event_id IS NULL;

ALTER TABLE team_members
    DROP INDEX uk_team_members_user,
    ADD UNIQUE KEY uk_team_members_event_user (event_id, user_id),
    ADD KEY idx_team_members_event_team (event_id, team_id);
