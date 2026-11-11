
-- 南邮身份与本站账号的一对一关联，不保存学校密码、密文或令牌。
CREATE TABLE IF NOT EXISTS njupt_identities (
    subject VARCHAR(32) NOT NULL,
    user_id VARCHAR(40) NOT NULL,
    PRIMARY KEY (subject),
    UNIQUE (user_id)
);
