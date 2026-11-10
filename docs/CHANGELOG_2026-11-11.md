# 2026-11-11 当前后端版本变更说明

## 比较范围

- 仓库：QAQxianyuQAQ/0xtrace-backend；PR 目标：main。
- 上次本地提交：494bdbc（整合学校认证和运行时锁修复的合并提交）。以下“新增改动”指该提交之后、现已部署但此前尚未提交的源码。
- 当前 main 基线：db9e2497492d091584f13726a84c7ce97a504be9。PR 还会包含已存在于功能分支、尚未进入 main 的提交，见文末。
- 本次源码对应后端发布标签：tracequest-backend:runtime-entry-fix-20261111。发布包仅用于版本核对，不随 Git 提交。

## 上次本地提交以来的新增改动

### 管理员权限与学校登录

- 补齐普通管理员列表与授予/撤销接口，权限限定最高管理员；角色更新原子递增 token_version，使目标旧会话失效。
- 保护最高管理员和操作者本人，不能通过普通角色接口提升、撤销或冒充最高管理员；不会根据用户名自动给既有账号提权。
- /auth/me 以兼容的 role=admin 表达管理员身份，并增加 isSuperAdmin 区分管理范围。
- NJUPT 首次绑定仍只创建普通选手；再次登录按独立身份映射保留既有角色、队伍与成绩，已停用账号仍拒绝登录。

### 队伍管理

- 新增后台当前赛事队伍分页查询及改名，继续通过既有选手管理接口调整归属；同赛事队伍名不可重复。
- 增加选手本人退出当前赛事队伍，重复退出幂等返回 204；不删除原队伍、历史提交、积分或运行实例。
- 队伍人数硬上限为 3（赛事可设置 1–3）；入队和管理员分配按事务锁定队伍并统计，防止并发超员。
- 旧超员队伍不自动踢人，后续入队被拒绝；生产升级不应通过重新初始化或重跑 V1 修改已有数据。

### 靶机启动与网页入口

- Docker 操作前保存 PENDING，随后保存 PULLING / STARTING / READY / FAILED，正常轮询不再阻塞等待 Docker 命令。
- 复用同队正在启动/已就绪的同题实例；为启动阶段保留合理预算，端口就绪后才开始一小时 TTL，并支持超时启动记录恢复。
- 优先使用管理员已预加载的题目镜像；只有缺失才拉取（最多 45 秒），docker run 禁止隐式拉取。同名标签更新须先由管理员预加载。
- 镜像不可用返回受控 503 / CONTAINER_RUNTIME_ERROR；选手可见 lastError 不透传 Docker 原始输出或 Flag。
- 有效 READY 响应增加 URL 单段编码的 entryPath，例如 /entry/MISSING/，沿用既有同源、队伍鉴权的 HTTP 网关。无实例、未就绪、停止、失败或到期时没有有效入口。
- 不能把 containerIp/hostIp/hostPort 当公网网址；entryPath 与票据保护的 TCP-over-WebSocket 是不同访问方式。
- V6 将 challenge_container_instances.container_port 改为可空，使镜像端口未知时也可先保存启动状态；H2 fixture 同步。既有库须备份、核对约束后手工迁移，已完成 V6 的生产库不要再次执行。

## 接口变化

具体请求、权限、错误码和示例以 [API.md](../API.md) 为准；同步的前端文档位于 Levia808/0xtrace_Frontend 的 tracequest-app/docs/API.md。

| 接口 | 变化 |
| --- | --- |
| GET /api/v1/auth/me | 增加 isSuperAdmin，保留兼容 role |
| GET /api/v1/admin/accounts/administrators | 最高管理员分页读取普通管理员 |
| PATCH /api/v1/admin/accounts/{userId}/role | 最高管理员授予或撤销普通管理员 |
| GET /api/v1/admin/teams | 管理员分页读取当前赛事队伍 |
| PATCH /api/v1/admin/teams/{teamId} | 管理员修改当前赛事队伍名 |
| DELETE /api/v1/events/{eventId}/teams/me | 选手本人退出队伍，204 幂等 |
| POST/GET runtime instances 与续期响应 | 新增可选 entryPath；完善启动状态和受控错误 |

## 本次 PR 还整合的已有分支提交

main 尚未包含已在分支提交的同源附件下载与空提交 SQL 修复、学校认证与头像存储、学校认证配置透传、最高管理员学校登录、Docker 告警后的容器 ID 解析及不依赖参数元数据的分布式锁修复。本次保留这些既有改动，不把它们错误描述为今天才实现。

## 验证与边界

- Docker Java 25/Maven 全量执行含集成分组：95 项，93 通过、2 项真实存储条件跳过。已在本次独立发布副本重跑通过；远端 CI 结果另见 PR 检查。
- 本机真实 MySQL/Redis/Docker 生命周期、重复启动复用、空提交/零进度、WSS 票据到实际 HTTP、停止与权限拒绝已回归。
- 服务器 SRCFLAG 现有镜像的专属 Docker 探针约 2 秒就绪并返回 HTTP 200，探针已清理；入口字段补齐后，已有玩家 MISSING 容器 ID 和启动时间保持不变。
- 新后端健康 200 / 重启 0，Web、入口网关和共享服务未变；没有在本次源码归档中再次执行服务器部署或迁移。
- 旧测试选手账号 ACCOUNT_DISABLED，因此没有借该账号完成生产玩家登录态下的最终浏览器点击，也没有解禁、伪造会话或制造成绩。健康检查、单测和直接 Docker 探针不等于完整选手端到端验收。

## 发布纪律

本次仅将当前已部署版本归档到独立分支并发起 PR，不直接修改 main，也不再次执行生产部署。账号密码、JWT/Cookie、SSH/NJUPT 凭据文件、真实 .env、数据库备份、Docker 数据卷和本机任意密码登录补丁均不纳入提交。

发布前凭据扫描发现一处最高管理员测试用例的固定假口令匹配检测规则；已改成每次测试随机生成，仅改测试夹具，不放宽扫描器或生产认证。
