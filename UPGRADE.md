# 升级指导书：V1.2 → V1.3

| | |
| --- | --- |
| 升级前 | `main` @ `c3ce5c7`（版本号 1.2.1，`main` 与 `dev` 同点） |
| 升级后 | `dev` @ `009998d7` + 版本号提交（子 MCP 描述：同步捕获 + 手动覆盖 + 并入 Agent instructions，版本号 1.3.0） |
| 中间跨越 | 2 个提交 |
| 数据库迁移 | **1 个**（V3：`downstream_mcp` 加两列可空 CLOB，纯增量） |
| 预计停机 | 一次重启的时间（约 15～30 秒），**无法做到零停机**，原因见 §2.1 |

**一句话：换 jar 重启就完事，Flyway 自动加两列，配置一个不用改。Agent 侧不需要任何
动作；唯一可见变化是重连后 `initialize` 的 `instructions` 可能多出一段"子 MCP"清单。**

---

## 1. 这次升级带来什么

只有一件事：**子 MCP 也有描述了**。

以前 Agent 只能从聚合工具名的前缀（`子MCP名__原工具名`）猜每个子 MCP 是干什么的，
下游自己 `initialize` 时报的 `instructions` 在网关这里被直接丢弃。现在：

- 同步时**自动捕获**下游 `instructions`，存为子 MCP 的"原始描述"（只读；同步失败保留上次捕获）
- 管理端（界面或 `PUT`）可填**自定义描述**，非空时覆盖原始，留空清除、回退原始
- 网关对 Agent 的 `instructions` 改为**组合式**：网关描述 + 有描述的子 MCP 清单

| 变更 | 对运维的影响 |
| --- | --- |
| `downstream_mcp` 表加 `original_description` / `custom_description` 两列（V3 迁移） | 启动时 Flyway 自动执行，纯增量，存量行为与 1.2.1 一致 |
| 网关 `instructions` 变成组合式 | Agent **重连后**拿到的说明文本会变；不重连不受影响。工具、令牌、凭证全都不动 |
| `PUT /api/gateways/{id}/mcp-servers/{serverId}` 新增 `customDescription` 字段 | **自己写脚本调管理 API 的注意**：这是 PUT 全量语义，不传 = 清除覆盖，不是"不改" |
| 管理接口响应里每个子 MCP 多 `originalDescription` / `customDescription` / `effectiveDescription` 三字段 | 纯增量，旧客户端忽略即可 |
| 管理界面子 MCP 卡片多"描述"编辑器 | 无 |
| 版本号 1.2.1 → 1.3.0 | jar 文件名跟着变。systemd 的 `ExecStart` 如果写死了版本号，要一起改 |

### 1.1 新增的环境变量

**没有。** 原有的环境变量一个都没动，照抄即可。

---

## 2. 升级前必须知道的事

### 2.1 必须先停后起，做不了蓝绿并行

数据库连接串是 `jdbc:h2:file:...;AUTO_SERVER=FALSE`，**H2 文件库是独占锁**。新旧两个进程
同时指向同一个数据文件，后起的那个会直接启动失败。

所以升级只能是：停旧 → 起新。不要试图先起新实例再切流量。这一条历次升级相同。

### 2.2 数据库迁移：一个，纯增量，自动执行

`V3__downstream_mcp_description.sql` 就两条语句：

```sql
ALTER TABLE downstream_mcp ADD COLUMN original_description CLOB;
ALTER TABLE downstream_mcp ADD COLUMN custom_description CLOB;
```

- 两列都**可空**，存量行两列都是 `NULL` —— 效果是"这些子 MCP 没有描述"，组合 instructions
  时跳过它们，与 1.2.1 的输出完全一致
- 迁移在启动时由 Flyway 自动执行，**不需要手工跑任何 SQL**
- 启动日志应出现 `Migrating schema "PUBLIC" to version "3"` 和 `Successfully applied 1 migration`
- 新依赖：**没有**（前后端都没有）

### 2.3 Agent 平面：不需要任何动作

- **网关访问令牌继续有效**，不用轮换，不用通知 Agent 改配置
- 子 MCP 凭证的加密格式、工具快照、调用记录全都在原地
- 令牌错、路径、超时这些协议行为一概没动

两个无害的可见变化：

1. MCP `initialize` 响应里服务端自报的 `version` 会从 `1.2.1` 变成 `1.3.0`（取自 build-info）
2. Agent **重连**后拿到的 `instructions` 文本可能变：网关描述之外会多出一段
   `子 MCP：\n- <名>：<描述>`（只列有描述的子 MCP；一个都没有就只有网关描述）。
   网关这边改描述后不会主动推送 —— 与工具变更一样，Agent 下一次 `initialize` 才看得到

### 2.4 回滚比上次多一步：数据库要连着一起回

V3 会记进 `flyway_schema_history`。1.2.1 的 jar 里没有 V3 这个文件，启动时 Flyway 校验
会发现"已应用但本地不存在"的迁移，**直接启动失败** —— 不是性能问题，是起不来。

所以这次回滚 = 换回旧 jar **＋恢复 §3.0 备份的数据库文件**，缺一不可。代价是升级之后
才写入的描述（自定义的、捕获的）会跟着备份时点丢掉，其余数据也回到备份那一刻。

---

## 3. 升级步骤

### 3.0 先做的两件事

```bash
# 一、备份数据库文件。这是唯一不可再生的东西，这次回滚也靠它
sudo systemctl stop mcp-gateway          # 或你的停服方式
cp -a /opt/mcp-gateway/data \
      /opt/mcp-gateway/data.bak-$(date +%Y%m%d)

# 二、留一份当前 jar，回滚时要用
cp /opt/mcp-gateway/mcp-gateway.jar /opt/mcp-gateway/mcp-gateway.jar.v1.2.1
```

> 库里含子 MCP 返回的正文（需求 FR-06.4），备份文件同样需要按部署要求保护。

### 3.1 jar 部署

**1. 在能联网的机器上构建**（不要在目标服务器上打包）：

```bash
git fetch origin
git checkout dev            # 确认在版本号 1.3.0 的提交或更新
git log -1 --format='%h %s'

mvn -B clean package
# 产物：target/mcp-gateway-1.3.0.jar
```

> 要挂子路径的照旧用 `mvn -Dvite.base.path=... -DskipTests -Dfrontend.test.skip=true clean package`，
> 与上次相同的三处同步规则见 [DEPLOY.md](DEPLOY.md#挂在子路径下)。这次的功能与子路径无交互。

**2. 停掉旧实例**：

```bash
sudo systemctl stop mcp-gateway
ss -lntp | grep 8080        # 确认端口已释放，H2 的锁跟着进程走
```

**3. 换 jar 并启动**：

```bash
cp target/mcp-gateway-1.3.0.jar /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
sudo journalctl -u mcp-gateway -f
```

**环境变量一个都不用动。** 启动日志里应该看到 `Migrating schema "PUBLIC" to version "3"`、
`Successfully applied 1 migration` —— 这次有迁移，看到它才是对的。

> systemd 的 `ExecStart` 如果写的是 `mcp-gateway-1.2.1.jar` 这种带版本号的文件名，
> 记得一起改；写的是 `mcp-gateway.jar` 就不用动。

### 3.2 Docker Compose 部署

compose 文件这次**没有结构改动**，只有镜像 tag 从 `1.2.1` 变成 `1.3.0`。自定义过 compose
文件的人只改 tag 一行即可。

```bash
docker compose down
docker compose build          # 依赖没变，不需要 --no-cache
docker compose up -d
docker compose logs -f        # 同样应看到 V3 迁移 applied
```

数据在具名卷 `mcp-gateway-data` 里，`down` 不会删它（`down -v` 才会 —— **不要加 `-v`**）。

---

## 4. 升级后验证清单

**① 服务活着**

```bash
curl -s http://127.0.0.1:8080/actuator/health
# 期望 {"status":"UP"}
```

**② 换上去的确实是 1.3.0**

jar 里的 `build-info` 就是版本号的唯一来源（`GatewayVersion` 读的也是它）：

```bash
unzip -p /opt/mcp-gateway/mcp-gateway.jar META-INF/build-info.properties | grep version
# 期望 build.version=1.3.0
```

看文件名不算数 —— 复制的时候改个名就对不上了。

**③ 界面和数据都在**

浏览器打开 `{baseUrl}/`，登录后网关列表条数与升级前一致；随便点一个进详情页，子 MCP
和聚合工具都在，**headers 显示为遮罩值 `******`**（说明凭证解密正常）。子 MCP 卡片里
多出一栏**描述**：上面一行"原始"（升级后是空的，点一次「测试并同步」就会捕获下游自述），
下面是自定义描述输入框 —— 这就是新功能落地了的直接证据。

**④ Agent 还能用（最关键的一条）**

用**升级前就在用的那个令牌**打一次，不要换新令牌：

```bash
curl -s -X POST 'http://127.0.0.1:8080/mcp/<你的slug>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer <原有令牌>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

期望正常返回工具列表，内容与升级前一致。想顺便看新行为，把 `method` 换成 `initialize`
（params 照 MCP 规范带 `protocolVersion` / `capabilities` / `clientInfo`），返回的
`instructions` 就是组合后的文案。

---

## 5. 回滚

**先停服，再把两样都换回去**（只换 jar 不还原库，旧 jar 起不来，见 §2.4）：

```bash
sudo systemctl stop mcp-gateway
rm -rf /opt/mcp-gateway/data
cp -a /opt/mcp-gateway/data.bak-$(date +%Y%m%d) /opt/mcp-gateway/data
cp /opt/mcp-gateway/mcp-gateway.jar.v1.2.1 /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
```

Docker 的数据在具名卷里，多一步把备份放回卷：

```bash
docker compose down
# 把备份的 .mv.db 放回卷（示例用 alpine 临时容器）
docker run --rm -v mcp-gateway-data:/data -v "$PWD":/backup alpine \
  sh -c 'rm -f /data/*.mv.db* && cp /backup/data.bak-*/mcp-gateway.* /data/'
git checkout <1.2.1 对应的提交>
docker compose build && docker compose up -d
```

升级期间新产生的调用记录和描述会随回滚丢掉 —— 备份是升级前那一刻的快照，这是代价本身。

---

## 6. 常见问题

**重连后 Agent 拿到的 instructions 变了，是坏了吗**
是预期变化：instructions 改成"网关描述 + 子 MCP 清单"的组合式。只动文案，工具列表、
令牌、协议行为都不受影响。不想要子 MCP 清单，把子 MCP 的自定义描述清空、且别触发
重新同步（捕获为空就不列）。

**自己写的脚本编辑子 MCP 之后，自定义描述总是丢**
`PUT` 的 `customDescription` 是**全量语义**：不传、`null`、空白都表示清除覆盖。脚本
每次都要显式带上这个字段，没有"不传 = 不改"（与 `headers` 的三态、工具 PATCH 的三态
都不同，详见 USAGE.md §5.2）。

**怎么让"原始"那行有内容**
点详情页里的「测试并同步」：网关连一次下游，把下游 `initialize` 的 `instructions`
捕获下来。下游没报过 instructions 就一直是空的，这是正常状态。

**回滚后旧 jar 起不来，日志有 `applied migration not resolved locally`**
数据库没有跟着回。按 §5 把备份的库一起还原 —— Flyway 校验到"已应用但 jar 里不存在"
的 V3 会拒绝启动，这是刻意的防呆，不是故障。

**只改了子 MCP 的描述，怎么没触发重新同步**
就是这样设计的：描述来自网关本地（捕获值或人工覆盖），不来自下游的工具目录，改它
不需要去摸下游。`PUT` 响应里 `syncResult` 为 `null` 即"这次没同步"。

---

## 7. 这次升级不需要做的事

写在这里是为了省掉不必要的动作：

- **不需要**新增或修改任何环境变量
- **不需要**轮换网关访问令牌
- **不需要**重新导入或重新配置任何子 MCP
- **不需要**改 `MCP_GATEWAY_MASTER_KEY`
- **不需要**执行任何 SQL —— V3 迁移启动时自动跑
- **不需要**改 Nginx / 反代配置
- **不需要**清缓存、清 localStorage
- **不需要**通知 Agent 做任何事（它们重连后自然拿到新 instructions）
