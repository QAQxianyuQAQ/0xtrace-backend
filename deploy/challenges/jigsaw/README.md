# 容器题出题/部署手册（异形数独拼图）

## 1. 目录结构

```
容器题/
├── Dockerfile              镜像定义（python:3.12-alpine，非 root 运行）
├── docker-compose.yml      本机自测用（平台上线不需要）
├── build_push.ps1          一键：查环境 → 构建 → 自检 → 可选推送
├── challenge.yaml          平台登记信息（标题/分类/分值/镜像/端口/flag 环境变量）
├── 题面.md                  给选手看的题面（贴到平台的题目描述里）
├── app/
│   ├── server.py           服务端（只用标准库）：静态页 + /api/health + /api/submit
│   └── index.html          玩家页面（由仓库根的 make_page.py 生成，**不含答案**）
├── data/
│   ├── puzzle.json         公开题面：碎片形状 + 提示数（进镜像）
│   └── answer.sha256       标准答案的 sha256（进镜像；镜像里没有明文答案）
├── tools/
│   └── make_puzzle.py      出题机工具：生成上面两个 data 文件（**不进镜像**）
└── test_local.py           不开 Docker 的端到端自测（22 项断言）
```

## 2. 出题流程（改了题目之后按这个顺序重跑）

```powershell
cd F:\Desktop\SANXIANGBEI
python verify.py                    # ① 数学验证：唯一拼法 + 唯一解
python make_page.py                 # ② 生成 异形数独拼图.html / 打印版.svg / 容器题/app/index.html
python 容器题\tools\make_puzzle.py  # ③ 生成 容器题\data\puzzle.json 与 answer.sha256
python 容器题\test_local.py         # ④ 端到端自测（不需要 Docker）
powershell -File 容器题\build_push.ps1 -Tag 1.0            # ⑤ 构建镜像（Docker Desktop 需先启动）
powershell -File 容器题\build_push.ps1 -Tag 1.0 -Push      # ⑥ 推送到镜像仓库
```

> ⚠ `answer.sha256` 必须和 `app/index.html` 出自同一份 `puzzle_9x9.json`。改题后**一定要 ②③ 一起重跑**，
> 否则会出现「页面拼对了但提交被拒」的假故障（`test_local.py` 里的 T6 就是专门防这个的）。

## 3. 运行容器（平台侧）

```bash
docker run --rm -p 8080:8080 -e FLAG='SACC{这里放本题真正的flag}' sacc/jigsaw-sudoku:1.0
```

平台登记时需要一致的三件事：**镜像 tag**、**容器端口 8080**、**flag 环境变量名**（默认 `FLAG`）。

### 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `FLAG` | 无（必填） | 正确 flag。为空或未设置 → 容器**立刻报错退出**（退出码 2），不会带空 flag 上线 |
| `FLAG_ENV` | `FLAG` | 放 flag 的环境变量名。平台若要求别的名字（如 `GZCTF_FLAG`），把它改成那个名字即可，代码不用动 |
| `PORT` | `8080` | 监听端口 |
| `BIND` | `0.0.0.0` | 监听地址 |
| `TTL_SECONDS` | `7200` | 容器存活秒数，到点自动退出（平台回收容器），避免长期挂机 |
| `SUBMIT_LIMIT_PER_MIN` | `20` | 每 IP 每分钟提交上限，超出返回 429 |
| `DEV_RELOAD` | 空 | `1` = 每次请求重读 `index.html`（本机调试用，平台不要开） |

启动日志会打印题面规模、flag 环境变量名与长度（**不打印 flag 明文**）、TTL 与限流配置。

## 4. 安全设计（防作弊）

- **镜像里没有明文答案**：`regions`、`solution`、`givens` 都不进镜像，只有题面（碎片形状 + 提示数）和答案的 `sha256`。
  即使有人 `docker cp` / 解包镜像，也拿不到解。
- **flag 只在提交正确时返回**，且不写进镜像层（只从环境变量读）；日志也不打印 flag 明文。
- **服务端校验**：先检查碎片是否越界/重叠/铺满（用公开数据就能查，报错信息不泄题），再对
  `sha256(规范化答案)` 做常量时间比较（`hmac.compare_digest`）。
- **限流**：每 IP 每分钟上限，防脚本爆破。
- **到点自动退出**：TTL 结束容器自行退出，不留长期服务。
- **选手页面里没有答案**：页面只注入 `{N, pieces, submit:true}`，且 `SOL = DATA.solution || null`，
  连「提示一格」按钮在容器模式下都会被移除。

> 规范化答案串格式（两端必须逐字符一致，改动请同步改两处并重跑 `test_local.py`）：
> ```
> sacc-jigsaw-sudoku-v1|N=9|<块序号>:<dr>:<dc>,…|<81 个数字逗号分隔>
> ```
> 服务端：`app/server.py::canonical`；出题工具：`tools/make_puzzle.py::canonical`。

## 5. 本机验证（已跑通）

| 测试 | 覆盖 | 结果 |
|---|---|---|
| `python verify.py` | 唯一拼法 / 唯一解 / 提示数一致（独立重写的求解器） | 全通过 |
| `python 容器题\test_local.py` | 缺 FLAG 报错退出、health、页面不泄题、非法拼法被拒、答案错被拒、正确返回 flag、自定义 `FLAG_ENV`、限流 429、TTL 自动退出 | 22 项全通过 |
| 浏览器级 E2E | 真起 server → Chrome 打开页面 → 自动拼好并填完 → 点「提交答案」→ 页面弹出 FLAG（`SACC{browser-e2e-…}`） | 通过 |

## 6. 常见问题

- **`docker build` 报 `failed to connect to the docker API at npipe://…dockerDesktopLinuxEngine`**
  → Docker Desktop 没启动。启动它，等托盘鲸鱼图标变绿再跑 `build_push.ps1`。
- **容器起来又立刻退出，日志有 `FATAL 环境变量 FLAG 没有设置`**
  → 运行命令少了 `-e FLAG='SACC{...}'`，或者平台登记里 flag 环境变量名与 `FLAG_ENV` 不一致。
- **页面能打开，但提交总是「不正确」**
  → 大概率是 `data/answer.sha256` 与 `app/index.html` 不是同一份题：回到第 2 节重跑 ②③。
- **平台用自己的 flag 环境变量名**
  → 只改环境变量 `FLAG_ENV=平台用的名字`，镜像不用重做。
- **选手抱怨 2 小时不够**
  → 调大 `TTL_SECONDS`；平台一般也有整场比赛的时限。
