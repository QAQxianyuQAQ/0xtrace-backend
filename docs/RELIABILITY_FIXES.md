# 2026-10-25 · TQ-01–14 修复与回归

## 范围与代码基线

- 后端基于 QAQxianyuQAQ/0xtrace-backend 的 beac0cce4df4c5abda12ad474cd4164b4308778f，保留本机发布副本已有的下载权限、同源、题目行锁及空查询补丁，再修复本清单。
- 前端基于 Levia808/0xtrace 的 80150ab68abae13b375e275f2a0a3ef30519289c，同步当前 Vue/React Hero、本地视频和 Java API 客户端，再修复密码边界及下载代理。
- 两端分支均为 fix/reliability-20261025；不推送/合并 main，不触发生产发布；新增前端PR流水线只测试和构建。
- 构建、测试与真实故障注入均在本机 Docker，测试项目仅0xtrace-functional-20261025，未访问共享服务器。

## 修复项

| 项 | 已实现的修复 | 验证 |
|---|---|---|
|TQ-01|下载逐请求重新鉴权，流式返回原文件，关闭旧/storage签名代理|暂停/窗口/匿名/角色、真实Range206/416、浏览器下载|
|TQ-02|真实格式与MIME一致并完成受限单帧解码|伪造、损坏PNG拒绝；PNG/JPEG/WebP/AVIF真实字节哈希|
|TQ-03|显式清空封面引用并失效缓存|删除后公开详情、文件接口均不再保留引用|
|TQ-04|进度总分包含该赛事队伍人工调整|+13/-3、队友、榜单与进度一致|
|TQ-05|CSV复用JSON账号约束，逐行失败，2MiB上限|合法/重复/非法用户名/密码、摘要与数据库结果|
|TQ-06|导出字段防公式前缀转义，再做CSV结构转义|=+-@、空白/制表/回车、逗号引号中文；未运行Excel|
|TQ-07|文件名预校验、回滚精确清理任务、先新后旧替换封面|真实SQL触发器造成批量入库失败，旧封面仍可下载，后台清理完成|
|TQ-08|创建与编辑均启用嵌套校验，分值和奖励单字段上限100万|创建/PATCH负数400，单题相加不溢出|
|TQ-09|S3删除失败返回错误并保留元数据|实际停止独立MinIO，恢复后下载一致且可重试删除|
|TQ-10|资料空白显式NULL，未传字段不变|保存、重新读取、浏览器刷新；管理员可清字段同样修复|
|TQ-11|BCrypt信任边界统一72个UTF-8字节上限|72ASCII/24汉字合法，73ASCII/25汉字受控400；客户端同步|
|TQ-12|参数绑定错误归类统一400|非法分页、超int范围、原有归一化仍通过|
|TQ-13|body键也在业务前绑定账号/题目/来源/内容|同题换答案、跨题、答案/位置核验混用均冲突|
|TQ-14|唯一键认领/业务/响应快照同一数据库事务|双JVM同键6并发完全一致；不同内容只有一次副作用；失败回滚后原键可重试|

## 实际验证

- Java25离线Maven：21/21（含H2接口场景，不冒充真实基础设施测试）。
- 前端Node20：14/14，Vite生产构建通过；保留既有use-client/大chunk警告。
- 真实MySQL/Redis/MinIO全栈：103/103，225次HTTP调用。
- 追加深度HTTP：27/27，含多格式图片、非法输入、跨题及6并发。
- 两个独立JVM并发/回滚：5/5，含同头不同内容仅1条错误提交、仅body键6并发只1条提交、跨来源409、组队前409回滚后原键在另一JVM成功。
- 真实数据库写故障/MinIO停机恢复：8/8；精确清理队列归零。
- 严格TLS Chromium实际UI：9/9，含后台上传3附件+PNG、刷新、独立选手登录、3个真实下载文件名及内容、得分/排名/完成态和越权阻断；无未捕获JS异常，未使用ignoreHTTPSErrors。
- S3核对：本轮新增孤儿0、缺失对象0；之前失败测试留下的4个孤儿与2个悬空封面引用未删除/改写，保留旧证据。
- 后端既有密钥扫描通过，提交排除.env/private/TLS私钥、Cookie、真实密码、构建产物和原始下载签名日志。

## 部署与回退

1. 先备份数据库/对象与现有镜像固定值；不更换凭据，不重置数据卷。
2. 对目标项目数据库执行 src/main/resources/sql/V3__storage_cleanup.sql（幂等增表，无DELETE/ALTER旧业务表）。应用生产模式不会自动改表；H2脚本只用于测试。
3. 后端运行镜像需要FFmpeg（本仓库Dockerfile已安装），保持Java25和非root运行。镜像只通过内网访问MinIO；不开放MinIO宿主端口。
4. 前端完整Java栈使用deploy/compose.full.yaml和deploy/nginx.conf，沿用已有私有infra/application/Redis/TLS配置。旧Node api目标、旧compose.production.yaml不适用于此Java全栈；不要再启动旧模拟API。前端旧HTTP nginx.conf也已切/api/v1到backend:8080并禁止/storage。
5. 同时更新后端镜像与前端网关，再使用现有HTTPS验证。镜像固定值按private/infra.env在前、images.env在后加载，显式OX_*覆盖，防止旧env反向覆盖；只重建web/backend，不重建基础数据服务。
6. 回退先恢复旧后端与web镜像固定值。新清理表可保留，不执行DROP或卷删除；已完成的物理对象清理不可用SQL回滚撤销，需依赖备份。

## 可复现测试入口

后端tests/functional包含functional.mjs、deep-api.mjs、concurrency.mjs、storage-cases.mjs、browser.cjs。真实账号配置须通过容器只读挂载到/work/private/infra.env（仅TQ_ADMIN_USERNAME/TQ_ADMIN_PASSWORD），TLS证书通过NODE_EXTRA_CA_CERTS注入；不随仓库提交。functional生成仅合成的/work/private/fixtures.json；deep另将同目录挂载/fixtures。/out用于本机报告，不应提交原始认证响应。

Node容器必须在独立测试网络，设置TEST_PROJECT=0xtrace-functional-20261025，核心脚本还要求OX_DEV_ONLY=LOCAL_SYNTHETIC_ONLY。测试会创建合成赛事、账号、题目、提交和文件，不能指向生产。双实例测试另要求无宿主端口的tq-reliability-replica服务，同一测试库和配置；注入SQL仅限同一一次性测试数据库，结束须DROP该测试触发器并恢复MinIO。不要修改全局MySQL安全变量。

## 尚未覆盖/边界

这不是全站安全认证或生产压力测试。真实摄像头/现场到达、生产容量、表格软件实际执行及视频许可未在此验证。自助注册、关于CRUD、头像文件上传、服务端二维码PNG仍待开发，不属于这14类失败修复。下载已开始或数据已取得时无法远程收回；静态码仍可转发。S3和数据库不是分布式原子事务：数据库同时失联导致清理任务无法入库时会记录精确键要求人工恢复，删除后发生数据库提交失败时需要对象备份。没有自动清理历史孤儿或修改共享服务器。
