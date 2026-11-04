# NIBBLE / 贪吃蛇挑战

一个两关的 Node.js CTF Web 挑战：第一关考察客户端事件重放，第二关考察隔离 JavaScript realm 中的对象合并与原型链。

## 本地运行

需要 Node.js 22 或更高版本：

```bash
npm start
```

浏览器打开 <http://localhost:3000>。也可以使用 Docker Compose：

```bash
docker compose up --build
```

Compose 使用单一镜像标签 `sacc/snake-ctf:latest`，进入项目目录后可直接一行部署：

```bash
docker compose up -d --build
```

## 验证

```bash
npm run check
npm test
```

端到端测试会覆盖健康检查、会话 Cookie、请求来源校验、第一关重放、第二关通关和重置流程。服务进程启动时会生成一个 UUID v7，并以 `SACC{UUID}` 作为本次运行的 Flag；同一进程内所有会话共享该 Flag。

蛇撞墙或撞到自己后，当前关卡分数会归零；第二关会重新创建计分 realm，需重新完成本关挑战。第二关结算分数达到或超过目标时，服务端会立即标记完成并返回 Flag。

## 项目结构

- `server.mjs`：HTTP 服务、会话状态和关卡 API。
- `lib/level-two.js`：第二关使用的独立 realm 计分模块。
- `public/`：贪吃蛇前端和页面样式。
- `test/server.test.mjs`：Node 内置测试运行器的端到端测试。

第二关源码接口仅在第一关完成后开放；这是挑战设计的一部分。生产环境部署时请通过环境变量设置唯一 Flag，并保留容器的只读文件系统与非 root 用户配置。
