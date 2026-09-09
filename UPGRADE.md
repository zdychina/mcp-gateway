# 升级指导书：`main` → `dev`

| | |
| --- | --- |
| 升级前 | `main` @ `ae80286`（版本号改从 build-info 读，不再写死在代码里） |
| 升级后 | `dev` @ `238b78a`（文档补上落下的四个提交） |
| 中间跨越 | 9 个提交 |
| 数据库迁移 | 1 个（`V2__call_record_started_at_index.sql`，纯加索引） |
| 预计停机 | 一次重启的时间（约 15～30 秒），**无法做到零停机**，原因见 §2.1 |

**一句话：必须新增一个环境变量 `MCP_GATEWAY_ADMIN_PASSWORD`（≥12 位），不设应用起不来。
其余全部向后兼容 —— Agent 不受影响，令牌不用换，数据不用迁。**

---

## 1. 这次升级带来什么

对运维有影响的只有第一条，其余是功能增量。

| 变更 | 对运维的影响 |
| --- | --- |
| **管理端加了登录**（单账号、会话 Cookie） | **必须**新增 `MCP_GATEWAY_ADMIN_PASSWORD`；升级后所有人访问管理界面都要先登录 |
| 新增总览页 `/ui/dashboard` | 无。附带一个数据库索引（V2 迁移，自动执行） |
| 调用记录页重做（可配置列、正文抽取、跳页、导出 Excel） | 无。新增 `poi-ooxml` 依赖，jar 会变大一些 |
| 子 MCP 支持填表单新增、改地址自动重新同步 | 无 |
| 网关列表页补 MCP 地址列和调用记录入口 | 无 |
| 下游 TLS 校验开关（默认关闭 = 照常校验） | 无。默认行为和升级前完全一致 |
| 界面可访问性修复 | 无 |

### 1.1 新增的环境变量

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `MCP_GATEWAY_ADMIN_PASSWORD` | **是** | 无 | 管理端登录口令。缺失或**短于 12 位时应用启动失败** |
| `MCP_GATEWAY_ADMIN_USERNAME` | 否 | `admin` | 管理端登录用户名 |
| `MCP_GATEWAY_COOKIE_SECURE` | 否 | `false` | **做了 TLS 反代的必须设为 `true`**，否则会话 Cookie 会在明文连接上也照发；反过来明文 HTTP 部署设成 `true` 会导致 Cookie 不下发、登录不进去 |
| `MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY` | 否 | `false` | 关掉子 MCP 的 TLS 校验。**保持默认** —— 只有内网自签证书且拿不到根证书时才考虑，见 [SECURITY.md](SECURITY.md#下游-tls-校验) |

原有的环境变量**一个都没有改动**，照抄即可。

---

## 2. 升级前必须知道的四件事

### 2.1 必须先停后起，做不了蓝绿并行

数据库连接串是 `jdbc:h2:file:...;AUTO_SERVER=FALSE`，**H2 文件库是独占锁**。新旧两个进程
同时指向同一个数据文件，后起的那个会直接启动失败。

所以升级只能是：停旧 → 起新。不要试图先起新实例再切流量。

### 2.2 Agent 平面完全不受影响

`/mcp/**` 那条链路**一行代码都没改**（`src/main/java/com/mcpgateway/mcpserver/` 在这 9 个提交里
零差异）。具体说：

- **网关访问令牌继续有效**，不用轮换，不用通知 Agent 改配置
- 令牌的哈希方式和子 MCP 凭证的加密格式都没变（`AccessTokenService`、`AesGcmCipher` 无差异），
  所以**不需要重新导入任何子 MCP**
- 管理端的登录**不会**波及 Agent —— 它走单独一条无状态过滤器链，只认 `Authorization: Bearer`

唯一的影响是重启期间那十几秒，Agent 的调用会失败。

### 2.3 升级后所有人都要重新登录

管理界面从"打开就能用"变成"先登录"。升级完成后：

- 浏览器里开着管理页面的人会被路由守卫送到 `/ui/login`
- **改口令要重启**，界面上没有改密入口，也没有找回密码通道
- 会话存在服务端内存里，**以后每次重启都会全员掉线**（闲置 30 分钟也会超时）

准备口令时注意：不短于 12 位，且只能来自环境变量。

### 2.4 回滚是安全的（已实测）

V2 迁移只是 `CREATE INDEX`，没有改表结构、没有动数据。带着 V2 的数据库退回 `main` 版本的
jar，Flyway 只打一条警告然后正常启动：

```
WARN  o.f.core.internal.command.DbMigrate : Schema "PUBLIC" has a version (2) that is newer
      than the latest available migration (1) !
INFO  Started McpGatewayApplication in 12.832 seconds
```

这条是实测验证过的，不是照文档推断的。那个多出来的索引会留在库里，旧版本用不到但也不会碍事。

---

## 3. 升级步骤

### 3.0 先做的三件事

```bash
# 一、备份数据库文件。这是唯一不可再生的东西
sudo systemctl stop mcp-gateway          # 或你的停服方式，见 3.1 第 2 步
cp /opt/mcp-gateway/data/mcp-gateway.mv.db \
   /opt/mcp-gateway/data/mcp-gateway.mv.db.bak-$(date +%Y%m%d)

# 二、留一份当前 jar，回滚时要用
cp /opt/mcp-gateway/mcp-gateway.jar /opt/mcp-gateway/mcp-gateway.jar.main-ae80286

# 三、想一个管理端口令（≥12 位），先存到密码管理器里
openssl rand -base64 18
```

> **`data/` 目录里可能还有 `.trace.db` 等文件，备份整个目录最省事。**
> 库里含子 MCP 返回的正文（需求 FR-06.4），备份文件同样需要按部署要求保护。

### 3.1 jar 部署

**1. 在能联网的机器上构建**（不要在目标服务器上打包）

```bash
git fetch origin
git checkout dev            # 确认在 238b78a
git log -1 --format='%h %s'

mvn -B clean package
# 产物：target/mcp-gateway-1.0.0.jar
```

> 这次新增了 `spring-boot-starter-security` 和 `poi-ooxml` 两个依赖。
> **离线/内网构建环境需要先确认这两个能拉到**，否则构建会停在依赖解析阶段。
> 前端构建还需要能访问 nodejs.org 和 npm registry。

**2. 停掉旧实例**

```bash
sudo systemctl stop mcp-gateway
# 确认端口已释放，H2 的锁跟着进程走
ss -lntp | grep 8080
```

**3. 补环境变量**

在你的 systemd unit / `.env` / 启动脚本里加上：

```bash
MCP_GATEWAY_ADMIN_PASSWORD=<刚才生成的口令>
MCP_GATEWAY_ADMIN_USERNAME=admin              # 可选，默认就是 admin
MCP_GATEWAY_COOKIE_SECURE=true                # 前面有 TLS 反代才设 true，否则保持 false
```

原有的 `MCP_GATEWAY_MASTER_KEY` 等**一个都不要改**。主密钥改了，库里已加密的子 MCP 凭证
就全部解不开了。

**4. 换 jar 并启动**

```bash
cp target/mcp-gateway-1.0.0.jar /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
sudo journalctl -u mcp-gateway -f
```

启动日志里应该看到 V2 迁移执行：

```
Migrating schema "PUBLIC" to version "2 - call record started at index"
Successfully applied 1 migration to schema "PUBLIC", now at version v2
```

### 3.2 Docker Compose 部署

`Dockerfile` 只改了注释，`docker-compose.yml` 新增了四个环境变量。

**1. 更新 compose 文件**（用仓库里的新版覆盖，或手工加这四行）

```yaml
environment:
  MCP_GATEWAY_ADMIN_PASSWORD: ${MCP_GATEWAY_ADMIN_PASSWORD:?请先设置 MCP_GATEWAY_ADMIN_PASSWORD}
  MCP_GATEWAY_ADMIN_USERNAME: ${MCP_GATEWAY_ADMIN_USERNAME:-admin}
  MCP_GATEWAY_COOKIE_SECURE: ${MCP_GATEWAY_COOKIE_SECURE:-false}
  MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY: ${MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY:-false}
```

`:?` 的写法意味着**没设口令时 compose 会直接拒绝启动并报出变量名**，不会起一个半残的容器。

**2. 在 `.env` 里补上口令，然后重建**

```bash
echo 'MCP_GATEWAY_ADMIN_PASSWORD=<口令>' >> .env

docker compose down
docker compose build --no-cache      # 依赖变了，别用旧的构建缓存
docker compose up -d
docker compose logs -f
```

数据在具名卷 `mcp-gateway-data` 里，`down` 不会删它（`down -v` 才会 —— **不要加 `-v`**）。

---

## 4. 升级后验证清单

按顺序走一遍，任何一步不对就按 §5 回滚。

**① 服务活着**

```bash
curl -s http://127.0.0.1:8080/actuator/health
# 期望 {"status":"UP"}
```

**② 迁移到位**

```bash
sudo journalctl -u mcp-gateway | grep -i "now at version"
# 期望 now at version v2
```

**③ 管理端要登录了**

浏览器打开 `{baseUrl}/`，应当落到 `/ui/login`。用刚设的口令登录，进入网关列表页。

**④ 旧数据都在**

登录后确认：网关列表条数和升级前一致，随便点一个进详情页，子 MCP 和聚合工具都在，
**headers 显示为遮罩值 `******`**（说明凭证解密正常 —— 主密钥没被改动）。

**⑤ Agent 还能用（最关键的一条）**

用**升级前就在用的那个令牌**打一次，不要换新令牌：

```bash
curl -s -X POST 'http://127.0.0.1:8080/mcp/<你的slug>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer <原有令牌>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

期望正常返回工具列表。**这一条通过，就说明令牌和加密数据都没受影响。**

**⑥ 新功能可用**

- 顶部导航点「总览」，四个数字和图表能出来
- 进某个网关的调用记录页，右上角「列」能打开、「导出 Excel」能下载

**⑦ TLS 反代场景额外查一条**

如果前面有 Nginx 做 TLS：登录之后刷新页面，**不应该被弹回登录页**。
被弹回就是 `MCP_GATEWAY_COOKIE_SECURE` 没设成 `true`。

---

## 5. 回滚

V2 迁移不阻碍回滚（见 §2.4），所以回滚就是把 jar 换回去。

```bash
sudo systemctl stop mcp-gateway
cp /opt/mcp-gateway/mcp-gateway.jar.main-ae80286 /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
```

Docker：`docker compose down && git checkout main && docker compose build && docker compose up -d`

**环境变量不用删** —— 旧版本不认识 `MCP_GATEWAY_ADMIN_PASSWORD`，多设一个不影响它启动。

回滚后 Flyway 会打那条 `version (2) is newer than the latest available migration (1)` 的警告，
这是预期的，可以忽略。

**只有一种情况需要动数据库**：升级后新产生的调用记录，回滚到旧版本仍然能正常读写
（表结构没变），所以**不需要恢复备份**。备份是给"数据文件损坏"这种意外准备的，不是给回滚的。

---

## 6. 常见问题

**启动即失败，日志里是 `MCP_GATEWAY_ADMIN_PASSWORD is not configured`**
口令没设，或者设了但没传进进程。systemd 要确认写在 `Environment=` 或 `EnvironmentFile=` 里。

**启动即失败，`must be at least 12 characters`**
口令短于 12 位。异常信息里不会回显你设的值 —— 启动失败的日志经常被整段贴进工单。

**登录页填对口令却一直退回登录页**
`MCP_GATEWAY_COOKIE_SECURE` 和实际协议对不上。TLS 反代后面要 `true`，明文 HTTP 要 `false`。

**登录失败次数多了之后一直提示"已被临时锁定"**
按来源 IP 限速，连续 5 次锁 5 分钟。**反向代理后面所有请求的来源 IP 都是代理**，
此时限速会退化成全局的 —— 一个人锁住，所有人都进不来。等 5 分钟即可。

**忘了口令**
改环境变量后重启。没有找回通道，也没有界面上的改密入口。

**总览页打开慢**
第一次打开要下载 ECharts 的分块（实测产物 519 KB / gzip 约 180 KB），它是路由级懒加载的，
只在打开总览时才下载。之后走浏览器缓存。

**升级后调用记录页的列变回默认了**
列配置存在**浏览器本地**（localStorage）、按网关区分。换浏览器或清了站点数据就要重配。

---

## 7. 这次升级不需要做的事

写在这里是为了省掉不必要的动作：

- **不需要**轮换网关访问令牌
- **不需要**重新导入或重新配置任何子 MCP
- **不需要**改 `MCP_GATEWAY_MASTER_KEY`（改了反而会让已加密的凭证全部解不开）
- **不需要**手工执行任何 SQL —— V2 由 Flyway 在启动时自动执行
- **不需要**清空或迁移调用记录
- **不需要**改 Nginx / 反代配置（路径和端口都没变）
