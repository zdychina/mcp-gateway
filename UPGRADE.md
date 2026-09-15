# 升级指导书：V1.1 → V1.2

| | |
| --- | --- |
| 升级前 | `main` @ `1cb035f`（版本号升到 1.1.0，`main` 与 `dev` 同点） |
| 升级后 | `dev` @ `56aa4bc`（支持挂在子路径下部署 + 重定向修复，版本号 1.2.1） |
| 中间跨越 | 3 个提交（其中 1 个是本指导书自己，纯文档） |
| 数据库迁移 | **0 个**。库结构和数据一个字节都不动 |
| 预计停机 | 一次重启的时间（约 15～30 秒），**无法做到零停机**，原因见 §2.1 |

**一句话：不用改任何配置，换 jar 重启就完事 —— 除非你要把应用挂到子路径下，那时有三处
必须同时改，见 §3.2。Java 源码零改动，Agent 完全不受影响。**

---

## 1. 这次升级带来什么

只有一件事：**支持把应用挂在子路径下**，例如 `https://host/kbmcp`。

默认部署里本应用占着整个域名根路径：`/ui`（管理界面）、`/api`（管理接口）、`/app`（前端资源）、
`/mcp`（Agent 端点）。同一个域名上已经有别的应用占了 `/api` 之类的地址时，以前只能换域名
或换端口；现在可以把这四类地址整体搬到一个前缀下面。

| 变更 | 对运维的影响 |
| --- | --- |
| 新增 `MCP_GATEWAY_CONTEXT_PATH`，默认空 | **不设就是原样**。只有要挂子路径时才用得上 |
| 构建新增 `-Dvite.base.path` 参数，默认空 | 同上。Docker 对应的是 `VITE_BASE_PATH` 构建参数 |
| `docker-compose.yml` 的 `build` 从简写改成长格式（多了 `args`） | 自定义过 compose 文件的要手工合并这一段，见 §3.3 |
| **重定向改发相对地址** | 做了 TLS 反代的部署这条是修复：以前 `https://host/` 会收到 `Location: http://host/ui/gateways`，把人从 443 甩到 80 |
| 版本号 1.1.0 → 1.2.1 | jar 文件名跟着变。systemd 的 `ExecStart` 如果写死了版本号，要一起改 |

### 1.1 新增的环境变量

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `MCP_GATEWAY_CONTEXT_PATH` | 否 | 空 | 子路径部署的前缀，如 `/kbmcp`。**必须与构建时的 `-Dvite.base.path` 同值**，且反代不要剥前缀 |

原有的环境变量**一个都没有改动**，照抄即可。

### 1.2 默认行为与 1.1.0 完全一致（已实测）

不设 `MCP_GATEWAY_CONTEXT_PATH` 时，1.2.1 的 jar 实测结果：

```
/                 -> 302 /ui/gateways
/ui/gateways      -> 200，入口文档里的资源地址是 /app/assets/...（无前缀）
/api/gateways     -> 401（未登录）
/actuator/health  -> 200
Set-Cookie: XSRF-TOKEN=...; Path=/; SameSite=Strict
```

与升级前逐条相同。**不打算用子路径的话，这次升级对你来说只是换了个 jar。**

---

## 2. 升级前必须知道的三件事

### 2.1 必须先停后起，做不了蓝绿并行

数据库连接串是 `jdbc:h2:file:...;AUTO_SERVER=FALSE`，**H2 文件库是独占锁**。新旧两个进程
同时指向同一个数据文件，后起的那个会直接启动失败。

所以升级只能是：停旧 → 起新。不要试图先起新实例再切流量。

这一条与上次升级相同，不是这次引入的。

### 2.2 Agent 平面连一行代码都没改

`src/main/java/` 在这次升级里**零差异** —— 不是"改得少"，是 `git diff` 输出为空。
唯一动过的服务端文件是 `application.yml`，加的是一个默认空值的配置项。

所以：

- **网关访问令牌继续有效**，不用轮换，不用通知 Agent 改配置
- 令牌哈希方式、子 MCP 凭证的加密格式都没变，**不需要重新导入任何子 MCP**
- 调用记录、工具快照都在原地

唯一的影响是重启期间那十几秒，Agent 的调用会失败。

有一个无害的可见变化：MCP `initialize` 的响应里，服务端自报的 `version` 会从 `1.1.0`
变成 `1.2.1`（取自 build-info）。协议行为不变。

### 2.3 回滚没有任何代价

**这次没有数据库迁移**（`db/migration/` 下仍然只有 `V1` 和 `V2`，和升级前一样），
所以不存在"新库配旧 jar"的问题 —— 上次升级要专门验证的那件事，这次根本不会发生。

回滚就是把 jar 换回去，见 §5。

---

## 3. 升级步骤

### 3.0 先做的两件事

```bash
# 一、备份数据库文件。这是唯一不可再生的东西
sudo systemctl stop mcp-gateway          # 或你的停服方式
cp -a /opt/mcp-gateway/data \
      /opt/mcp-gateway/data.bak-$(date +%Y%m%d)

# 二、留一份当前 jar，回滚时要用
cp /opt/mcp-gateway/mcp-gateway.jar /opt/mcp-gateway/mcp-gateway.jar.v1.1.0
```

> 库里含子 MCP 返回的正文（需求 FR-06.4），备份文件同样需要按部署要求保护。

### 3.1 jar 部署（不挂子路径 —— 绝大多数情况）

**1. 在能联网的机器上构建**（不要在目标服务器上打包）

```bash
git fetch origin
git checkout dev            # 确认在 26a0544 或更新
git log -1 --format='%h %s'

mvn -B clean package
# 产物：target/mcp-gateway-1.2.1.jar
```

> **要挂子路径的别用这条命令** —— 它打出来的 jar 不带前缀，装上去是白屏。用 §3.2 那条。

> **依赖没有新增**，上次那两个新依赖（`spring-boot-starter-security`、`poi-ooxml`）已经在
> 1.1.0 里了。前端构建仍需能访问 nodejs.org 和 npm registry。

**2. 停掉旧实例**

```bash
sudo systemctl stop mcp-gateway
ss -lntp | grep 8080        # 确认端口已释放，H2 的锁跟着进程走
```

**3. 换 jar 并启动**

```bash
cp target/mcp-gateway-1.2.1.jar /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
sudo journalctl -u mcp-gateway -f
```

**环境变量一个都不用动。** 启动日志里应该看到 `Schema "PUBLIC" is up to date. No migration
necessary.` —— 这次没有迁移，看到它才是对的。

> systemd 的 `ExecStart` 如果写的是 `mcp-gateway-1.1.0.jar` 这种带版本号的文件名，
> 记得一起改；写的是 `mcp-gateway.jar` 就不用动。

### 3.2 要挂到子路径下（例如 `/kbmcp`）

**三处必须同时改，少一处就是坏的，而且坏法各不相同：**

| 改哪里 | 怎么写 | 漏了的症状 |
| --- | --- | --- |
| 构建 | `mvn -Dvite.base.path=/kbmcp -DskipTests -Dfrontend.test.skip=true clean package` | 页面能打开但资源全 404（白屏），接口打到同域的别的应用上 |
| 运行 | `MCP_GATEWAY_CONTEXT_PATH=/kbmcp` | 整个前缀 404 |
| 反代 | 转发时**不要**剥掉前缀 | 剥两次等于没设，同样 404 |

外加一个**不报错**的：`MCP_GATEWAY_BASE_URL` 要写到前缀为止
（`https://host/kbmcp`），否则给 Agent 的接入 URL 会少一截 —— 管理界面一切正常，
只有 Agent 连不上，而且不会有任何日志提示。

**前缀是打进 jar 的。** 前端资源地址在 `index.html` 里是绝对路径，只能构建期确定，所以同一份
产物不能既挂根路径又挂 `/kbmcp`；换前缀要重新构建，不是改个环境变量重启。

打完先验产物再传 —— 漏了参数的 jar 照样能打出来、服务照样能起来、页面照样返回 200，
只是白屏，一路到浏览器才发现：

```bash
unzip -p target/mcp-gateway-1.2.1.jar BOOT-INF/classes/static/app/index.html | grep -o 'src="[^"]*"'
# 期望 src="/kbmcp/app/assets/index-xxxx.js"
# 出现 src="/app/assets/..." 就是漏了 -Dvite.base.path，重打，别传
```

Nginx 的写法（`proxy_pass` 结尾**不带路径**，这正是"不剥前缀"的写法；一旦写成
`proxy_pass http://127.0.0.1:8080/;` 就会把前缀剥掉）：

```nginx
location /kbmcp/ {
    proxy_pass http://127.0.0.1:8080;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;

    # /kbmcp/mcp/{slug} 是 Streamable HTTP，响应可能是长连 SSE。
    # 缓冲开着的话事件会被 nginx 攒住不下发，Agent 看起来就是"卡住"。
    proxy_buffering off;
    proxy_cache off;
    proxy_read_timeout 3600s;
}

# 不带尾斜杠的 /kbmcp 也能进去
location = /kbmcp { return 301 /kbmcp/; }
```

顺带一个好处：会话 Cookie 和 CSRF 令牌 Cookie 的 `Path` 会自动收到 `/kbmcp` 上，
与同域其他应用在 `/` 上种的同名 Cookie 不会互相覆盖。

**从根路径改挂到子路径，等于换了地址。** 所有人的书签要更新；Agent 那边的接入 JSON
要按新的 `BASE_URL` 重发一遍（令牌不变，只是 URL 变了）。

详见 [DEPLOY.md](DEPLOY.md#挂在子路径下)。

### 3.3 Docker Compose 部署

`docker-compose.yml` 这次有一处结构改动：`build: .` 改成了长格式，多了 `args`。

```yaml
    build:
      context: .
      args:
        VITE_BASE_PATH: ${MCP_GATEWAY_CONTEXT_PATH:-}
    image: mcp-gateway:1.2.1
```

**自定义过 compose 文件的人要手工合并这一段**，直接用仓库里的新版覆盖会丢掉你的改动。
不打算用子路径的话，这段照抄即可，空值等同于原来的行为。

环境变量部分新增一行（同样默认空）：

```yaml
      MCP_GATEWAY_CONTEXT_PATH: ${MCP_GATEWAY_CONTEXT_PATH:-}
```

**一个变量同时喂给构建参数和运行环境**，所以构建期和运行期不可能对不上 —— §3.2 那张表里
前两行的"少一处"在 compose 部署下不会发生。

```bash
docker compose down
docker compose build          # 依赖没变，这次不需要 --no-cache
docker compose up -d
docker compose logs -f
```

要挂子路径的话，先在 `.env` 里加一行再 build：

```bash
echo 'MCP_GATEWAY_CONTEXT_PATH=/kbmcp' >> .env
echo 'MCP_GATEWAY_BASE_URL=https://host/kbmcp' >> .env   # 别忘了这条
docker compose build && docker compose up -d
```

> 改了前缀**必须重新 `build`**，光 `up -d` 不会重打前端 —— 资源地址已经烤进镜像里了。

数据在具名卷 `mcp-gateway-data` 里，`down` 不会删它（`down -v` 才会 —— **不要加 `-v`**）。

---

## 4. 升级后验证清单

不挂子路径的走 ①～④，挂了子路径的把地址都加上前缀再走一遍，并补上 ⑤。

**① 服务活着**

```bash
curl -s http://127.0.0.1:8080/actuator/health
# 期望 {"status":"UP"}
```

**② 换上去的确实是 1.2.1**

jar 里的 `build-info` 就是版本号的唯一来源（`GatewayVersion` 读的也是它）：

```bash
unzip -p /opt/mcp-gateway/mcp-gateway.jar META-INF/build-info.properties | grep version
# 期望 build.version=1.2.1
```

看文件名不算数 —— 复制的时候改个名就对不上了。

**③ 界面和数据都在**

浏览器打开 `{baseUrl}/`，落到登录页，登录后网关列表条数与升级前一致；随便点一个进详情页，
子 MCP 和聚合工具都在，**headers 显示为遮罩值 `******`**（说明凭证解密正常）。

**④ Agent 还能用（最关键的一条）**

用**升级前就在用的那个令牌**打一次，不要换新令牌：

```bash
curl -s -X POST 'http://127.0.0.1:8080/mcp/<你的slug>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer <原有令牌>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

期望正常返回工具列表。

**⑤ 子路径部署要额外查两半**

前缀内通、前缀外不通，**两半都要看**。只看前一半的话，"前缀没生效、应用还占着根路径"
这种情况是发现不了的：

```bash
curl -sI http://127.0.0.1:8080/kbmcp/ui/gateways | head -1   # 期望 200
curl -sI http://127.0.0.1:8080/ui/gateways       | head -1   # 期望 404

# 入口文档里的资源地址必须带着前缀，否则就是构建时漏了 -Dvite.base.path
curl -s http://127.0.0.1:8080/kbmcp/ui/gateways | grep -o 'src="[^"]*"'
# 期望形如 src="/kbmcp/app/assets/index-xxxx.js"
```

再到管理界面上打开任意一个网关的详情页，确认**接入 JSON 里的 URL 带着前缀**
（`https://host/kbmcp/mcp/<slug>`）。不带前缀就是 `MCP_GATEWAY_BASE_URL` 没改 ——
这是唯一一个不会报错、只有 Agent 那边能发现的坑。

---

## 5. 回滚

没有数据库迁移要退，回滚就是把 jar 换回去。

```bash
sudo systemctl stop mcp-gateway
cp /opt/mcp-gateway/mcp-gateway.jar.v1.1.0 /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
```

Docker：`docker compose down && git checkout <上一个提交> && docker compose build && docker compose up -d`

**环境变量不用删** —— 旧版本不认识 `MCP_GATEWAY_CONTEXT_PATH`，多设一个不影响它启动
（Spring Boot 只是忽略掉没人读的环境变量）。

**但如果你已经挂上了子路径，回滚就等于把地址改回根路径**：1.1.0 的 jar 不认 context-path，
应用会重新占据 `/ui` `/api` `/app` `/mcp`。此时必须同步做两件事：

1. 把 Nginx 的 `location /kbmcp/` 改回去（或直接删掉），否则前缀下是 404
2. 把 `MCP_GATEWAY_BASE_URL` 去掉前缀，并通知 Agent 换回不带前缀的 URL

换句话说：**不挂子路径时回滚零成本；挂了子路径之后回滚要连带回退反代和接入地址。**
这是决定要不要用子路径时就该知道的事，不是回滚当天才发现的。

---

## 6. 常见问题

**子路径下页面白屏，控制台一堆资源 404**
构建时漏了 `-Dvite.base.path`，或者它与 `MCP_GATEWAY_CONTEXT_PATH` 不是同一个值。
看一眼入口文档就知道：`curl -s {baseUrl}/kbmcp/ui/gateways | grep -o 'src="[^"]*"'`，
地址里没有前缀就是这个问题。重新构建，别指望改环境变量能救。

**子路径下整个前缀 404**
两种可能：运行时没设 `MCP_GATEWAY_CONTEXT_PATH`；或者反代把前缀剥掉了 ——
`proxy_pass` 结尾带了路径（`http://127.0.0.1:8080/`）就会剥，去掉那个斜杠。

**管理界面一切正常，但 Agent 连不上**
`MCP_GATEWAY_BASE_URL` 没跟着改。它是**唯一不会报错**的一处：接入 JSON 里的地址
刻意不从 `Host` / `X-Forwarded-*` 推断（需求 FR-05.1），所以只能靠配置，配错了没人拦。

**子路径下登录后一刷新就退回登录页**
先查 `MCP_GATEWAY_COOKIE_SECURE`（TLS 反代后面要 `true`）。与前缀无关 ——
Cookie 的 `Path` 会自动收到前缀上，不需要手工配。

**Docker 改了 `.env` 里的前缀但没生效**
只 `up -d` 不够，前端资源地址烤在镜像里，必须 `docker compose build`。

**`docker compose up` 报 `build` 格式错误**
compose 文件只合并了一半 —— `build: .` 和 `build:` 长格式不能共存，照 §3.3 整段替换。

**升级后调用记录页的列变回默认了**
列配置存在**浏览器本地**（localStorage）、按网关区分。这次升级不动它；但如果你**改了前缀**，
浏览器眼里那是另一个源，本地存的列配置和主题偏好都要重设一次。

---

## 7. 这次升级不需要做的事

写在这里是为了省掉不必要的动作：

- **不需要**新增任何环境变量（除非要挂子路径）
- **不需要**轮换网关访问令牌
- **不需要**重新导入或重新配置任何子 MCP
- **不需要**改 `MCP_GATEWAY_MASTER_KEY`
- **不需要**执行任何 SQL —— 这次一个迁移都没有
- **不需要**清缓存、清 localStorage（不改前缀的话）
- **不需要**改 Nginx / 反代配置（不改前缀的话，路径和端口都没变）
