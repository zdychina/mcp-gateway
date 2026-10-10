# 升级指导书：V1.3 → V1.4

| | |
| --- | --- |
| 升级前 | `dev` @ `441e1e5`（版本号 1.3.0） |
| 升级后 | `dev` @ 本次功能提交 + 版本号提交（instructions 组合模板化，版本号 1.4.0） |
| 数据库迁移 | **0 个** |
| 预计停机 | 一次重启的时间（约 15～30 秒），**无法做到零停机**，原因见 §2.1 |

**一句话：换 jar 重启就完事，没有数据库迁移，配置一个不用改。不设新环境变量的话，
Agent 看到的 `instructions` 与 1.3 逐字节相同 —— 这次升级对 Agent 平面是完全透明的。**

---

## 1. 这次升级带来什么

两件事：**`instructions` 的组合格式可以自定义了**，以及详情页多了一段**组合结果预览**。

1.3 里网关对 Agent 的 `instructions` 是写死的拼接（网关描述 + 「子 MCP：」清单）。
现在这段拼接变成一个**全局模板**，通过环境变量换成自己的格式：

```bash
MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE='【{{gatewayDescription}}】

可用子服务：
{{downstreams}}'
```

- 两个占位符：`{{gatewayDescription}}` = 网关描述，`{{downstreams}}` = 有描述的子 MCP 清单
- 模板按空行分段，解析为空的占位符所在段**整段省略**（子 MCP 都没描述时标题不会残留）
- 含未知或残缺的 `{{...}}` 时**启动失败**，而不是把字面 `{{` 发给 Agent
- 模板在启动时读入，改动需重启；已连接的 Agent 要重连才能拿到新文案

| 变更 | 对运维的影响 |
| --- | --- |
| `instructions` 组合格式模板化 | **不设新环境变量就没有任何变化**：默认模板渲染结果与 1.3 逐字节相同 |
| 新增可选环境变量 `MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE` | 想自定义格式时才设；设错了（未知占位符）应用起不来，日志会点名是哪个 |
| 详情页「Agent 接入」段新增 instructions 预览 | 界面上能看到组合后的完整文案，不用打 `initialize` 验证 |
| 版本号 1.3.0 → 1.4.0 | jar 文件名跟着变。systemd 的 `ExecStart` 如果写死了版本号，要一起改 |

### 1.1 新增的环境变量

**一个，可选**：`MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE`。不设 = 用内置默认模板。
原有的环境变量一个都没动。

---

## 2. 升级前必须知道的事

### 2.1 必须先停后起，做不了蓝绿并行

数据库连接串是 `jdbc:h2:file:...;AUTO_SERVER=FALSE`，**H2 文件库是独占锁**。新旧两个进程
同时指向同一个数据文件，后起的那个会直接启动失败。

所以升级只能是：停旧 → 起新。不要试图先起新实例再切流量。这一条历次升级相同。

### 2.2 数据库迁移：零个

这次的变更全在代码和配置里，`db/migration/` 下没有新文件。启动日志里**不应该**出现
`Migrating schema` 行（schema 停在版本 3，这正是对的状态）。新依赖：**没有**（前后端都没有）。

### 2.3 Agent 平面：不需要任何动作

- **网关访问令牌继续有效**，不用轮换，不用通知 Agent 改配置
- **`instructions` 默认输出与 1.3 逐字节相同** —— 这是本次的设计承诺，由测试锁死
  （默认模板的渲染结果与 1.3 写死的拼接做整串相等断言）
- 工具、令牌、凭证、协议行为一概没动

唯一的可见变化：MCP `initialize` 响应里服务端自报的 `version` 会从 `1.3.0` 变成 `1.4.0`
（取自 build-info）。

### 2.4 回滚比上次简单：只换 jar

这次没有数据库迁移，`flyway_schema_history` 不会多任何记录，1.3.0 的 jar 拿着同一个库
直接就能起。回滚 = 换回旧 jar 重启，**不需要还原数据库**（升级前备份仍是惯例，但不再
是回滚的前提）。唯一会"丢"的是升级期间设过的自定义模板 —— 它是环境变量，跟着部署走，
本来就不在库里。

---

## 3. 升级步骤

### 3.0 先做的两件事

```bash
# 一、备份数据库文件。这次回滚用不上它，但它是唯一不可再生的东西
sudo systemctl stop mcp-gateway          # 或你的停服方式
cp -a /opt/mcp-gateway/data \
      /opt/mcp-gateway/data.bak-$(date +%Y%m%d)

# 二、留一份当前 jar，回滚时要用
cp /opt/mcp-gateway/mcp-gateway.jar /opt/mcp-gateway/mcp-gateway.jar.v1.3.0
```

> 库里含子 MCP 返回的正文（需求 FR-06.4），备份文件同样需要按部署要求保护。

### 3.1 jar 部署

**1. 在能联网的机器上构建**（不要在目标服务器上打包）：

```bash
git fetch origin
git checkout dev            # 确认在版本号 1.4.0 的提交或更新
git log -1 --format='%h %s'

mvn -B clean package
# 产物：target/mcp-gateway-1.4.0.jar
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
cp target/mcp-gateway-1.4.0.jar /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
sudo journalctl -u mcp-gateway -f
```

**环境变量一个都不用动。** 这次启动日志里**没有** `Migrating schema` 行才是对的
（见 §2.2）。

> systemd 的 `ExecStart` 如果写的是 `mcp-gateway-1.3.0.jar` 这种带版本号的文件名，
> 记得一起改；写的是 `mcp-gateway.jar` 就不用动。

### 3.2 Docker Compose 部署

compose 文件的变化：镜像 tag 从 `1.3.0` 变成 `1.4.0`，外加一条**可选**的模板环境变量
透传（不设就是空，行为不变）。自定义过 compose 文件的人只改 tag 一行即可；要用自定义
instructions 模板的话，把自己那份 compose 里的 `environment:` 段补上：

```yaml
      MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE: ${MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE:-}
```

```bash
docker compose down
docker compose build          # 依赖没变，不需要 --no-cache
docker compose up -d
```

数据在具名卷 `mcp-gateway-data` 里，`down` 不会删它（`down -v` 才会 —— **不要加 `-v`**）。

---

## 4. 升级后验证清单

**① 服务活着**

```bash
curl -s http://127.0.0.1:8080/actuator/health
# 期望 {"status":"UP"}
```

**② 换上去的确实是 1.4.0**

jar 里的 `build-info` 就是版本号的唯一来源（`GatewayVersion` 读的也是它）：

```bash
unzip -p /opt/mcp-gateway/mcp-gateway.jar META-INF/build-info.properties | grep version
# 期望 build.version=1.4.0
```

看文件名不算数 —— 复制的时候改个名就对不上了。

**③ 界面和数据都在**

浏览器打开 `{baseUrl}/`，登录后网关列表条数与升级前一致；随便点一个进详情页，子 MCP
和聚合工具都在，**headers 显示为遮罩值 `******`**（说明凭证解密正常）。「Agent 接入」段
现在多了一块 **instructions 预览**（按当前模板现算的组合文案）—— 有子 MCP 描述的网关
能看到完整的两段式文案，这就是 ④ 要验证的内容在界面上的呈现。

**④ Agent 看到的 instructions 与升级前一字不差（最关键的一条）**

用**升级前就在用的那个令牌**打一次 `initialize`（params 照 MCP 规范带
`protocolVersion` / `capabilities` / `clientInfo`）：

```bash
curl -s -X POST 'http://127.0.0.1:8080/mcp/<你的slug>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -H 'Authorization: Bearer <原有令牌>' \
  -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
    "protocolVersion":"2025-06-18","capabilities":{},
    "clientInfo":{"name":"upgrade-check","version":"1"}}}'
```

返回的 `instructions` 应与升级前完全一致（除 `serverInfo.version` 变成 1.4.0）。
这就是"默认输出逐字节相同"的现场验证。

**⑤（可选）试一下自定义模板**

设上环境变量重启，再打一次上面的 `initialize`：

```bash
MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE='【{{gatewayDescription}}】

可用子服务：
{{downstreams}}'
```

`instructions` 应变成新格式。用完想回到默认，删掉环境变量重启即可。

---

## 5. 回滚

这次没有迁移牵连，**停服换回旧 jar 就行**：

```bash
sudo systemctl stop mcp-gateway
cp /opt/mcp-gateway/mcp-gateway.jar.v1.3.0 /opt/mcp-gateway/mcp-gateway.jar
sudo systemctl start mcp-gateway
```

Docker 同理（tag 换回 `1.3.0` 重新 build/up）。升级期间新产生的调用记录**不会**丢 ——
它们在库里，1.3.0 的 jar 读得到。

---

## 6. 常见问题

**设了模板之后应用起不来，日志里有 unknown placeholder**
模板里出现了 `{{gatewayDescription}}` / `{{downstreams}}` 之外的占位符，或者有写残的
`{{`（比如少右括号）。这是刻意的 fail-fast：字面 `{{` 发给 Agent 是只能从 Agent 行为
倒查的配置错误，宁可起不来。检查模板里每一个 `{{...}}` 的名字。

**改了模板环境变量，怎么 Agent 拿到的还是旧文案**
两道都过才有新文案：网关进程要**重启**（模板在启动时读入，不热更新），已连接的 Agent
要**重连**（instructions 在连接建立时定死）。先重启网关，再看 Agent 重连。

**模板里就想输出字面 `{{`**
不支持。会撞上未知占位符校验或残缺 `{{` 检查，应用起不来。换个写法（比如全角 `｛｛`）。

**子 MCP 的描述变了，网关的 instructions 会跟着变吗**
会，这与 1.3 相同：捕获的原始描述或自定义描述一变，网关丢弃缓存的 MCP 上下文，下一次
`initialize` 按当前模板重新渲染。模板本身不变，变的只是占位符填进去的内容。

---

## 7. 这次升级不需要做的事

写在这里是为了省掉不必要的动作：

- **不需要**新增或修改任何环境变量（想自定义 instructions 格式才设新变量）
- **不需要**轮换网关访问令牌
- **不需要**重新导入或重新配置任何子 MCP
- **不需要**改 `MCP_GATEWAY_MASTER_KEY`
- **不需要**执行任何 SQL —— 这次没有数据库迁移
- **不需要**改 Nginx / 反代配置
- **不需要**清缓存、清 localStorage
- **不需要**通知 Agent 做任何事（默认输出一字未变，它们什么都不会察觉）
