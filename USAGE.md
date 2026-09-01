# MCP 聚合网关 · 使用指南

面向使用者和运维的完整操作手册。设计取舍和内部实现见 [README.md](README.md)，安全走查见 [SECURITY.md](SECURITY.md)。

---

## 目录

1. [这个东西是干什么的](#1-这个东西是干什么的)
2. [五分钟跑通](#2-五分钟跑通)
3. [核心概念](#3-核心概念)
4. [管理界面操作](#4-管理界面操作)
5. [管理 API 参考](#5-管理-api-参考)
6. [Agent 接入](#6-agent-接入)
7. [配置参考](#7-配置参考)
8. [部署](#8-部署)
9. [运维与排障](#9-运维与排障)
10. [错误码速查](#10-错误码速查)
11. [边界与已知限制](#11-边界与已知限制)
12. [开发与测试](#12-开发与测试)

---

## 1. 这个东西是干什么的

把**多个下游 MCP Server 聚合成一个**，对 Agent 只暴露一个地址、一个令牌。

```
                       ┌──────────────────────────────────────┐
                       │            MCP 聚合网关               │
  Claude Code /        │                                      │      ┌──────────────┐
  其他 Agent    ──────▶│  /mcp/{slug}   ← 一个地址一个令牌     │─────▶│ 子 MCP wiki  │
                       │                                      │      └──────────────┘
   Bearer 令牌         │  tools/list  → 数据库工具快照         │      ┌──────────────┐
                       │  tools/call  → 按聚合名路由到子 MCP   │─────▶│ 子 MCP kb    │
                       │                                      │      └──────────────┘
                       │  每次调用留一条 tool_call_record      │      ┌──────────────┐
                       └──────────────────────────────────────┘─────▶│ 子 MCP docs  │
                                                                     └──────────────┘
```

**能做的**

- 一个网关聚合最多 3 个子 MCP（可配），子 MCP 的凭证由网关持有，不下发给 Agent
- 工具目录同步到本地快照，可以按工具启停、可以覆盖工具描述
- 每次 `tools/call` 落一条完整调用记录（入参、出参、耗时、状态、trace_id）
- 一个 Web 管理界面，不需要写配置文件
- 调用记录可以在界面上按工具、子 MCP、状态、trace_id 和时间范围查，展开看入参与返回

**不能做的**（MVP 明确的边界）

- 只支持 `streamable-http` 传输的下游，**不支持 stdio**（`command`/`args`/`env` 会被明确拒绝）
- 管理端**没有登录、没有权限系统**，靠网络边界保护
- 调用记录**没有清理或归档策略**，只在删除网关时级联删除，长期运行会持续增长
- 令牌轮换没有过渡期，轮换即刻断连

---

## 2. 五分钟跑通

### 2.1 前置

- Java 21+、Maven 3.9+（或 Docker）
- 仓库路径必须是**纯 ASCII**。含中文的路径在 `sun.jnu.encoding=GBK` 的 Windows 上会破坏 forked JVM 的命令行参数（JaCoCo 的 `-javaagent` 首当其冲），且无法通过 `MAVEN_OPTS` 修正

### 2.2 生成主密钥

网关用它做 AES-GCM 加密子 MCP 凭证。Base64 编码的 **32 字节**：

```bash
openssl rand -base64 32
```

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

### 2.3 启动

```bash
export MCP_GATEWAY_MASTER_KEY='<上一步的密钥>'
export MCP_GATEWAY_BASE_URL='http://127.0.0.1:8080'
mvn spring-boot:run
```

```powershell
$env:MCP_GATEWAY_MASTER_KEY = '<上一步的密钥>'
$env:MCP_GATEWAY_BASE_URL   = 'http://127.0.0.1:8080'
mvn spring-boot:run
```

或者跑已构建的 jar：

```bash
mvn -DskipTests package
java -jar target/mcp-gateway-0.1.0-SNAPSHOT.jar
```

启动后浏览器打开 <http://127.0.0.1:8080/>，会跳到网关列表页。

> **`MCP_GATEWAY_MASTER_KEY` 没有兜底默认值，不设就启动不来。** 这是刻意的（需求 12.1 / 12.2）：
> 有兜底值就意味着仓库里躺着一把公开的密钥，子 MCP 凭证用它加密等同于明文落库。
> `SecurityInvariantsTest.noHardcodedSecrets` 会在构建期断言
> `application.yml` 里这一行就是 `master-key: ${MCP_GATEWAY_MASTER_KEY:}`。

### 2.4 建一个网关

界面上点「新建网关」，或者：

```bash
curl -X POST http://127.0.0.1:8080/api/gateways \
  -H 'Content-Type: application/json' \
  -d '{"name":"知识库网关","slug":"kb","description":"内部文档与知识库工具集合"}'
```

响应里的 `data.accessToken` 就是 Agent 用的令牌，**只出现这一次**，服务端只存哈希：

```json
{
  "success": true,
  "data": {
    "gateway": {
      "id": "6f1e...", "name": "知识库网关", "slug": "kb",
      "status": "EMPTY", "mcpUrl": "http://127.0.0.1:8080/mcp/kb",
      "downstreams": []
    },
    "accessToken": "mcpgw_xxxxxxxxxxxxxxxxxxxxxxxx"
  },
  "error": null
}
```

**先把令牌存好再做下一步。** 丢了只能轮换，没有找回。

### 2.5 导入子 MCP

粘贴一份标准的 `mcpServers` JSON：

```bash
curl -X POST http://127.0.0.1:8080/api/gateways/<网关ID>/mcp-servers/import \
  -H 'Content-Type: application/json' \
  -d '{
    "mcpServers": {
      "wiki": {
        "type": "streamable-http",
        "url": "https://wiki.internal.example.com/mcp",
        "headers": { "Authorization": "Bearer wiki-token-xxx" }
      },
      "kb": {
        "type": "streamable-http",
        "url": "https://kb.internal.example.com/mcp"
      }
    }
  }'
```

导入后**立即自动同步一次**，响应里 `syncResults` 逐个给出成败：

```json
{
  "success": true,
  "data": {
    "gateway": { "status": "DEGRADED", "downstreams": [ "..." ] },
    "syncResults": [
      { "downstreamName": "wiki", "succeeded": true,  "added": 7, "updated": 0, "unchanged": 0, "removed": 0 },
      { "downstreamName": "kb",   "succeeded": false, "errorCode": "DOWNSTREAM_INIT_FAILED",
        "errorMessage": "DOWNSTREAM_INIT_FAILED: kb: connection refused" }
    ]
  },
  "error": null
}
```

配置是**整批原子落库**的（要么全写要么全不写），但同步是**逐个尝试**的 —— 一个下游连不上不影响其他。

### 2.6 拿接入 JSON 并接上 Agent

```bash
curl http://127.0.0.1:8080/api/gateways/<网关ID>/agent-config
```

```json
{
  "mcpServers": {
    "kb": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8080/mcp/kb",
      "headers": { "Authorization": "Bearer <gateway-access-token>" }
    }
  }
}
```

把 `<gateway-access-token>` 替换成 2.4 拿到的真令牌，粘进 Agent 的 MCP 配置即可。

验证一下：

```bash
curl -X POST http://127.0.0.1:8080/mcp/kb \
  -H 'Authorization: Bearer mcpgw_xxxxxxxx' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

应该看到形如 `wiki__search_pages`、`wiki__get_page` 的聚合工具名。

---

## 3. 核心概念

### 3.1 四层对象

| 对象 | 表 | 说明 |
| --- | --- | --- |
| **网关**（总 MCP） | `mcp_gateway` | 对 Agent 暴露的一个 MCP 端点，有独立 slug 和访问令牌 |
| **子 MCP**（下游） | `downstream_mcp` | 被聚合的真实 MCP Server，凭证加密存储 |
| **工具快照** | `gateway_tool` | 从子 MCP 同步来的工具定义 + 操作人的启停/描述配置 |
| **调用记录** | `tool_call_record` | 每次 `tools/call` 的两阶段打点 |

删网关会**级联删除**它的子 MCP、工具快照和调用记录；删子 MCP 会级联删除它的工具快照。

### 3.2 命名规则

| 项 | 规则 | 违反时 |
| --- | --- | --- |
| 网关 name | 非空，≤ 64 字符 | `INVALID_REQUEST` |
| 网关 slug | `^[A-Za-z0-9_-]{1,64}$`，全局唯一 | `INVALID_REQUEST` / `DUPLICATE_GATEWAY_SLUG` |
| 网关 description | ≤ 4000 字符，会成为 MCP `initialize` 的 `instructions` | `INVALID_REQUEST` |
| 子 MCP name | `^[A-Za-z0-9_.-]{1,64}$`，**不得含 `__`**，网关内唯一 | `INVALID_MCP_CONFIG` / `DUPLICATE_DOWNSTREAM_NAME` |
| 子 MCP url | 绝对 URL，仅 http/https，**不得含 user-info**，≤ 2048 字符 | `INVALID_MCP_CONFIG` |
| 聚合工具名 | `子MCP名称__原工具名`，须匹配 `^[A-Za-z0-9_.-]{1,128}$` | `INVALID_TOOL_NAME`，整次同步失败 |

`__` 是聚合命名的分隔符，所以子 MCP 名字里不能再出现它，否则聚合名读不回原始结构。

聚合名不合法或超过 128 字符时**整次同步失败**，不会静默截断或重命名 —— 否则 Agent 侧的工具名会在你不知情的情况下变化。

### 3.3 网关状态（派生，不落库）

| 状态 | 含义 | 可用 |
| --- | --- | --- |
| `EMPTY` | 没有子 MCP，或有子 MCP 但都还没同步过 | ✗ |
| `READY` | 所有子 MCP 最近一次同步都成功 | ✓ |
| `DEGRADED` | 部分子 MCP 异常，但仍有可用工具 | ✓ |
| `UNAVAILABLE` | 配置了子 MCP 但**全部**同步失败 | ✗ |

`UNAVAILABLE` 是实现补的第四态：需求只定义了前三态，而 `DEGRADED` 的定义是"仍有可用工具"，全挂的情况在三态里没有归属。

### 3.4 子 MCP 同步状态

| 状态 | 含义 |
| --- | --- |
| `PENDING` | 刚导入，还没同步过 |
| `SUCCESS` | 最近一次同步成功，快照有效 |
| `FAILED` | 最近一次同步失败，**保留上一次成功的快照**，`last_sync_at` 停在上次成功时刻 |

`last_sync_at` 的含义是"**快照有多新**"，不是"上次尝试是什么时候"。同步失败不会推进它。

### 3.5 同步的合并规则

重新同步时：

| 字段 | 行为 |
| --- | --- |
| 原始描述 / inputSchema / outputSchema / annotations | **覆盖**为下游最新值 |
| `enabled` 启停状态 | **保留**操作人的配置 |
| `customDescription` 自定义描述 | **保留**操作人的配置 |
| 下游已删除的工具 | 从快照移除 |
| 下游新出现的工具 | 新增，**默认启用** |

这条由 SQL 结构本身保证：更新协议字段的语句里根本没有 `enabled` 和 `custom_description` 两列。

同步结果里的四个计数：`added` 新增 / `updated` 定义有变已覆盖 / `unchanged` 定义指纹未变 / `removed` 下游已删除。

### 3.6 生效描述

```
effectiveDescription = customDescription 非空 ? customDescription : originalDescription
```

Agent 在 `tools/list` 里看到的就是这个值。清除自定义描述会回退到下游的原始描述。

---

## 4. 管理界面操作

浏览器打开 `{baseUrl}/`（默认 <http://127.0.0.1:8080/>），会跳到 `/ui/gateways`。

### 4.1 列表页 `/ui/gateways`

显示每个网关的名称、slug、状态、子 MCP 数量、工具数量、更新时间；可以新建和删除网关。
顶部有搜索框，按名称、slug 或描述过滤。

更新时间显示成相对时间（「3 分钟前」），鼠标悬停可看绝对时间。时间按**浏览器本地时区**
换算，不再跟着服务端的 JVM 时区走。

**创建网关后令牌会当场显示，直到你点「我已保存，关闭」为止。** 页面不会刷新，
所以不用担心令牌被冲掉 —— 但它确实只显示这一次，关掉之后只能靠轮换重新生成。

### 4.2 详情页 `/ui/gateways/{id}`

四段：

**基本信息** —— 改名称、slug、描述。
改 slug 会改变 Agent 侧的 MCP URL，是**破坏性变更**，已接入的 Agent 会连不上。

**子 MCP 配置** —— 导入、编辑、删除、单个「测试并同步」。

编辑子 MCP 时页面显示的 headers 是遮罩值 `******`。**必须先勾「替换 headers」** 才会提交这个字段；不勾则不提交，服务端保持原有凭证不变。把遮罩值原样提交回去会把真凭证覆盖掉。

「测试并同步」失败时页面给的是**警告**而不是错误，并会写明"已保留上一次成功的工具快照"——
同步失败不会让工具消失（需求 6.4.7）。导入时同理：配置一定整批落库，同步则逐个成败，
页面会把两件事分开说。

**聚合工具** —— 按子 MCP 分组，逐个启停、覆盖描述。启停立即生效，不需要重启或重新同步。
停用的工具整行会压暗。描述留空保存表示清除自定义描述、回退到下游的原始描述。

**Agent 接入** —— 复制 MCP 地址和接入 JSON；轮换令牌（新令牌在此当场显示一次，旧令牌立即失效）。

### 4.3 调用记录页 `/ui/gateways/{id}/calls`

从详情页右上角「查看调用记录」进去。

- 顶部**状态分面**给出各终态的条数，点一下就切换筛选，再点一次取消。
  这些数字**不受当前状态筛选影响**，所以可以直接拿来在 `SUCCESS` / `ERROR` 之间跳
- 可按聚合工具名（包含匹配，`kb_a__` 能筛出一整个子 MCP）、子 MCP、`trace_id` 和时间范围筛
- 点某一行**展开**看这次调用的入参和返回；列表本身不带正文，展开时才按 `callId` 取
- 点 `trace_id` 直接按那条链路筛（需求 15.4.3）
- 时间用本地时区显示，鼠标悬停看绝对时间

`STARTED` 的记录要么是正在进行，要么是上次进程异常退出遗留的 —— 后者会在下次启动时
被自动改成 `ERROR`。

**这个页面会显示子 MCP 返回的原始内容**，和数据库文件一样需要按部署要求保护。

### 4.4 界面的几处刻意取舍

- **不引用任何外网资源** —— 内网部署没外网时 CDN 会让界面直接不可用。样式和脚本
  全部随 jar 发布，`UiServingTest` 会在构建期验这一条
- **提示文案一律走文本插值，不拼 HTML** —— 错误信息里可能带下游返回的内容
- **变更后不整页刷新**，提示框会留到你自己关掉（成功提示 6 秒后自动消失）。
  这也意味着令牌显示出来之后不会被任何操作冲掉
- 确认框还是原生 `confirm`，等选定 UI 组件库后再换

### 4.5 前端实现

管理界面是 Vue 单页应用（`frontend/`），服务端只把 `/ui/**` 转发给它的入口文档，
数据全部走 `/api` 下的管理接口。直接访问、刷新、后退都能落到正确的页面上。

前端产物与后端**同源**打进同一个 jar，不做独立部署 —— 管理端没有登录，
独立部署必须放开的 CORS 会拆掉目前仅有的跨站保护。详见
[README 的管理界面一节](README.md#管理界面)。

---

## 5. 管理 API 参考

所有管理接口在 `/api` 下，**只接受 `application/json`**，响应结构统一：

```json
{ "success": true,  "data": { }, "error": null }
```

```json
{ "success": false, "data": null, "error": { "code": "GATEWAY_NOT_FOUND", "message": "no such gateway" } }
```

三个字段恒定存在。错误响应不含 Java 堆栈，`message` 已脱敏。

### 5.1 网关

#### `GET /api/gateways` — 列表

```bash
curl http://127.0.0.1:8080/api/gateways
```

返回 `GatewaySummaryResponse[]`：`id`、`name`、`slug`、`description`、`status`、`downstreamCount`、`toolCount`、`mcpUrl`、`createdAt`、`updatedAt`。

#### `POST /api/gateways` — 创建 → `201`

```json
{ "name": "知识库网关", "slug": "kb", "description": "可选，≤4000 字符" }
```

响应 `{ gateway, accessToken }`。**`accessToken` 明文只出现这一次。**

#### `GET /api/gateways/{id}` — 详情

返回 `GatewayDetailResponse`，含 `downstreams[]`，每个 downstream 里嵌了它的 `tools[]`。
`headers` 只有名称和遮罩值，真实凭证**永远不出现在任何 API 响应里**。

#### `PUT /api/gateways/{id}` — 编辑

```json
{ "name": "新名称", "slug": "new-slug", "description": "新描述" }
```

三个字段都是全量提交。改 slug 会改变 MCP URL。

#### `DELETE /api/gateways/{id}` — 删除

级联删除子 MCP、工具快照和调用记录。二次确认是前端的职责，API 不确认。

#### `GET /api/gateways/{id}/agent-config` — 接入 JSON

令牌位置是占位符 `<gateway-access-token>` —— 服务端只有哈希，拿不回明文。想要真令牌只能轮换。

#### `POST /api/gateways/{id}/access-token/rotate` — 轮换令牌

```json
{ "success": true, "data": { "accessToken": "mcpgw_...", "mcpUrl": "http://.../mcp/kb" }, "error": null }
```

**旧令牌立即失效，没有过渡期。** 在用的 Agent 会立刻断连。

### 5.2 子 MCP

#### `POST /api/gateways/{id}/mcp-servers/import` — 导入并同步

请求体是原始的 `mcpServers` JSON（不是包一层 DTO）：

```json
{
  "mcpServers": {
    "<子MCP名称>": {
      "type": "streamable-http",
      "url": "https://...",
      "headers": { "Authorization": "Bearer ..." }
    }
  }
}
```

规则：

- `type` 必填，且只能是 `streamable-http`；其他值 → `UNSUPPORTED_TRANSPORT`
- 出现 `command` / `args` / `env` 任一 → `UNSUPPORTED_TRANSPORT`（即使同时写了 `type`，这种配置自相矛盾，直接拒绝而不是猜）
- `url` 必填，规则见 [§3.2](#32-命名规则)
- `headers` 可省略，按空对象处理；值必须是字符串
- 本次最多能导入 `上限(默认3) − 已有数量` 个；超了 → `MCP_SERVER_LIMIT_EXCEEDED`

响应 `{ gateway, syncResults[] }`。

#### `POST /api/gateways/{id}/mcp-servers/{serverId}/sync` — 测试并同步

**同步失败也返回 HTTP 200** —— 请求本身处理成功了，失败的是与下游的交互，细节在 body 的 `succeeded` / `errorCode` / `errorMessage` 里。

```json
{ "success": true, "data": {
    "downstreamId": "...", "downstreamName": "wiki", "succeeded": true,
    "added": 1, "updated": 2, "unchanged": 4, "removed": 0,
    "errorCode": null, "errorMessage": null
  }, "error": null }
```

#### `PUT /api/gateways/{id}/mcp-servers/{serverId}` — 编辑

```json
{ "name": "wiki", "url": "https://wiki.internal.example.com/mcp" }
```

**`headers` 字段有三种语义，务必分清：**

| 提交内容 | 效果 |
| --- | --- |
| **不传** `headers` 键 | 保持原有凭证不变 ← 默认应该这么做 |
| `"headers": {}` | **清空**所有凭证 |
| `"headers": {"Authorization": "..."}` | 整体替换为新值 |

绝不要把 `GET` 拿到的遮罩值 `******` 原样提交回来 —— 那会把真凭证覆盖成字面的 `******`。

改名会连带改掉**所有**聚合工具名，对 Agent 是破坏性变更（启停状态和自定义描述会保留）。

响应是更新后的完整 `GatewayDetailResponse`。

#### `DELETE /api/gateways/{id}/mcp-servers/{serverId}` — 删除

它的工具立即从快照消失。响应是更新后的 `GatewayDetailResponse`。

### 5.3 工具

#### `PATCH /api/gateways/{id}/tools/{toolId}` — 启停 / 改描述

只有 PATCH，没有其他动词：工具行由同步流程创建和删除，操作人不能手工增删，也不能改名称或 Schema。

```json
{ "enabled": false }
```

```json
{ "customDescription": "面向财务同学的报表查询工具" }
```

**PATCH 语义：**

| 字段 | 不传 | 传 `null` 或空白串 | 传值 |
| --- | --- | --- | --- |
| `enabled` | 不改 | — | 设为该值 |
| `customDescription` | 不改 | **清除**，回退到原始描述 | 覆盖（≤ 4000 字符） |

响应是更新后的 `GatewayToolResponse`，含 `effectiveDescription`。改动**立即生效**，Agent 下次 `tools/list` 就能看到。

### 5.4 调用记录

#### `GET /api/gateways/{id}/call-records` — 分页列表

所有查询参数都可省略。最近的在前。

| 参数 | 说明 |
| --- | --- |
| `toolName` | 聚合工具名，**包含匹配**，大小写不敏感。用 `kb_a__` 可一次筛出某个子 MCP 的全部工具 |
| `downstreamMcpId` | 子 MCP，精确匹配 |
| `status` | `STARTED` / `SUCCESS` / `ERROR` / `TIMEOUT`，大小写不敏感 |
| `traceId` | 精确匹配（需求 15.4.3 的链路关联） |
| `from` | 起始时间，ISO-8601 instant，**含** |
| `to` | 结束时间，ISO-8601 instant，**不含** |
| `page` | 页码，从 0 开始，默认 0 |
| `size` | 每页条数，默认 20，**上限 100**（超了收敛到 100，响应里的 `size` 说明实际用了多少） |

```json
{
  "success": true,
  "data": {
    "items": [
      {
        "callId": "9f2c...", "traceId": "4bf92f...",
        "downstreamMcpId": "7a1e...", "exposedToolName": "kb_a__search",
        "originalToolName": "search", "status": "SUCCESS",
        "errorCode": null, "errorMessage": null,
        "startedAt": "2026-08-31T09:00:00Z", "finishedAt": "2026-08-31T09:00:00.120Z",
        "durationMs": 120
      }
    ],
    "page": 0, "size": 20, "total": 137,
    "statusCounts": { "SUCCESS": 120, "ERROR": 15, "TIMEOUT": 2 }
  },
  "error": null
}
```

两点必须知道：

- **列表不含 `requestJson` / `responseJson`。** 那两个字段各可能接近 1 MiB，装的是子 MCP
  返回的正文；一次几十条带上它们，等于在一个没有登录的接口上成批摊开业务内容。
  要看正文只能按 `callId` 取单条。
- **`statusCounts` 不套用 `status` 参数**，其余筛选条件照常生效。它是给界面做分面切换用的 ——
  已经筛了 `ERROR` 还只显示 `ERROR` 的数量，就没法拿它跳到别的状态。

非法的 `status`、`from`、`to`、负数 `page` 一律返回 `INVALID_REQUEST`，**不静默忽略** ——
静默忽略会让你以为"筛出来就这些"，而实际上根本没筛。

#### `GET /api/gateways/{id}/call-records/{callId}` — 单条完整内容

比列表多 `gatewayId`、`requestJson`、`responseJson` 三个字段。两个 JSON 以**字符串**返回，
不在服务端解析 —— 库里可能是打点时序列化失败写下的占位符 `{"_unserializable":true}`，
解析失败不该让整条记录取不出来。

记录**按网关隔离**：拿别的网关的 `callId` 来查，返回和"不存在"完全一致的
`CALL_RECORD_NOT_FOUND`，不会透露"它存在但属于别人"。

> 这是整个管理 API 里唯一会返回知识库正文的接口，而管理端没有登录 ——
> 它的暴露面等同于数据库文件本身，见 [SECURITY.md](SECURITY.md)。
> 反过来，记录里**不会**有凭证（需求 FR-06.3）。

---

## 6. Agent 接入

### 6.1 端点

```
POST {baseUrl}/mcp/{slug}
Authorization: Bearer <令牌>
Content-Type: application/json
Accept: application/json, text/event-stream
```

传输是 **Streamable HTTP**。`{baseUrl}` 来自部署配置 `MCP_GATEWAY_BASE_URL`，**不从 `Host` / `X-Forwarded-*` 等请求头拼接** —— 那些头不可信。

### 6.2 网关接管了什么

| 方法 | 谁处理 |
| --- | --- |
| `tools/list` | **网关**，每次现读数据库快照，只返回 `enabled = true` 的工具 |
| `tools/call` | **网关**，按聚合名路由到子 MCP，全程打点 |
| `initialize` / `ping` / 其他 | 官方 SDK 原样处理 |

`initialize` 返回的 `instructions` 就是网关的**描述**字段。

网关的 SDK server **不注册任何工具** —— 工具目录由网关自己每次从数据库读，所以启停、重新同步、改描述都立即生效，不需要重建 MCP 上下文。

### 6.3 调用结果与错误

下游返回的结果**原样透传**，包括下游自己标记的 `isError` 业务错误。

网关自己的错误走 JSON-RPC 错误，错误码在 `error.data.errorCode` 里，`message` 只有已脱敏的短句：

```json
{ "jsonrpc": "2.0", "id": 3, "error": {
    "code": -32602, "message": "no such tool",
    "data": { "errorCode": "TOOL_NOT_FOUND" } } }
```

JSON-RPC `code` 映射：`TOOL_NOT_FOUND` / `TOOL_DISABLED` / `INVALID_TOOL_ARGUMENTS` → `-32602`，其余 → `-32603`。

**停用工具和未知工具的对外文案完全一致** —— 不向 Agent 暴露"这个工具存在但被停用了"这类配置状态。

### 6.4 认证与 Origin

- 令牌不对或没带 → `401`，响应带 `WWW-Authenticate: Bearer`。不区分"没带"和"不对"，也不回显收到的值
- slug 不存在 → `404`
- 路径必须是 `/mcp/{slug}` **恰好一段**。`/mcp/a/mcp/b` 这类多段路径一律拒绝
- 带了 `Origin` 头且不在 `MCP_GATEWAY_ALLOWED_ORIGINS` 里 → `403`。**没带 `Origin` 的请求放行** —— Agent 这类非浏览器客户端不会带这个头；本机部署默认列表为空，即拒绝一切浏览器来源

### 6.5 超时与大小限制

| 项 | 默认 | 配置键 |
| --- | --- | --- |
| 下游调用超时 | 30s | `mcp-gateway.downstream.call-timeout` |
| 下游连接超时 | 10s | `mcp-gateway.downstream.connect-timeout` |
| Agent 请求体上限 | 1 MiB | `mcp-gateway.server.max-request-size` |
| 下游响应体上限 | 1 MiB | `mcp-gateway.downstream.max-response-size` |

超限 → `PAYLOAD_TOO_LARGE`；下游超时 → `DOWNSTREAM_TIMEOUT`。

---

## 7. 配置参考

### 7.1 环境变量

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `MCP_GATEWAY_MASTER_KEY` | **是** | 无 | Base64 编码的 32 字节 AES 主密钥。缺失、不是合法 Base64 或长度不对时**启动失败** |
| `MCP_GATEWAY_BASE_URL` | 建议 | `http://127.0.0.1:8080` | 接入 JSON 里的地址。必须是 **Agent 实际能访问到**的地址 |
| `MCP_GATEWAY_BIND_ADDRESS` | 否 | `127.0.0.1` | 管理端无登录，默认只监听 localhost |
| `MCP_GATEWAY_PORT` | 否 | `8080` | |
| `MCP_GATEWAY_DB_PATH` | 否 | `./data/mcp-gateway` | H2 文件库路径（不含 `.mv.db` 后缀） |
| `MCP_GATEWAY_ALLOWED_ORIGINS` | 否 | 空 | 逗号分隔的允许 Origin |
| `MCP_GATEWAY_DB_USER` | 否 | `sa` | |
| `MCP_GATEWAY_DB_PASSWORD` | 否 | 空 | |

### 7.2 application.yml 可调项

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `mcp-gateway.base-url` | `http://127.0.0.1:8080` | 见上 |
| `mcp-gateway.security.master-key` | — | 见上 |
| `mcp-gateway.security.allowed-origins` | `[]` | 见上 |
| `mcp-gateway.downstream.call-timeout` | `30s` | 下游 `tools/call` 超时 |
| `mcp-gateway.downstream.connect-timeout` | `10s` | 下游连接超时 |
| `mcp-gateway.downstream.max-response-size` | `1048576` | 下游响应体上限，最小 1024 |
| `mcp-gateway.server.max-request-size` | `1048576` | Agent 请求体上限，最小 1024 |
| `mcp-gateway.server.max-downstream-per-gateway` | `3` | 每网关子 MCP 上限，最小 1 |
| `management.endpoints.web.exposure.include` | `health` | **不要放开更多** —— 管理端没有登录 |

覆盖方式：环境变量（relaxed binding，如 `MCP_GATEWAY_DOWNSTREAM_CALL_TIMEOUT`）、`-D` 系统属性、或外置 `application.yml`。

### 7.3 数据库

H2 文件库，单机单进程（`AUTO_SERVER=FALSE`）。Flyway 在启动时自动跑 `src/main/resources/db/migration/` 下的迁移，`baseline-on-migrate: false`。

四张表：`mcp_gateway`、`downstream_mcp`、`gateway_tool`、`tool_call_record`。

> 库里含**知识库返回的原始内容**（`tool_call_record.request_json` / `response_json` 不截断保存），
> 必须按部署要求保护该文件。

---

## 8. 部署

### 8.1 本地 jar

```bash
mvn -DskipTests package
MCP_GATEWAY_MASTER_KEY='<key>' \
MCP_GATEWAY_BASE_URL='http://127.0.0.1:8080' \
java -jar target/mcp-gateway-0.1.0-SNAPSHOT.jar
```

### 8.2 Docker Compose

```bash
export MCP_GATEWAY_MASTER_KEY=$(openssl rand -base64 32)
export MCP_GATEWAY_BASE_URL=http://<Agent 能访问到的地址>:8080
docker compose up -d --build
```

容器化的两个坑：

1. **容器里必须监听 `0.0.0.0`**（Dockerfile 里已设 `MCP_GATEWAY_BIND_ADDRESS=0.0.0.0`），
   这就放弃了"默认只监听 localhost"那道保护。所以 compose 把端口**只发布到宿主机回环** `127.0.0.1:8080:8080`。
   改成 `0.0.0.0:8080:8080` 等于把一个**没有登录**的管理端交给整个网络。
2. **`MCP_GATEWAY_BASE_URL` 必须是 Agent 实际能访问到的地址**，不能是容器内部视角的 `127.0.0.1` ——
   它会原样写进接入 JSON。

容器已做的收紧：非 root 用户（uid 1001）、`read_only: true` + `tmpfs:/tmp`、`no-new-privileges`、数据放具名卷 `mcp-gateway-data`、`HEALTHCHECK` 打 `/actuator/health`。

镜像构建**不跑测试** —— 测试需要真实随机端口和临时数据库，属于 CI 的职责。CI 应先 `mvn verify` 通过再构建镜像。

### 8.3 内网部署清单

要把它开放给本机以外的用户，必须同时做到：

- [ ] 设 `MCP_GATEWAY_BIND_ADDRESS` 为具体内网地址（不是 `0.0.0.0`），或放在反向代理后面
- [ ] 反向代理上加认证（网关自己没有）
- [ ] `MCP_GATEWAY_BASE_URL` 设为代理对外的地址
- [ ] 需要浏览器跨源访问管理界面时，显式配 `MCP_GATEWAY_ALLOWED_ORIGINS`
- [ ] 保护 H2 文件（它含知识库返回的内容）
- [ ] 发布前跑一次 `mvn -Psecurity verify -Dnvd.api.key=<KEY>`

**绝不能直接暴露到公网。**

---

## 9. 运维与排障

### 9.1 健康检查

```bash
curl http://127.0.0.1:8080/actuator/health
```

只放开了 `health`，且 `show-details: never`。**不要放开其他 actuator 端点** —— 管理端没有登录。

### 9.2 查调用记录

**界面**：网关详情页右上角「查看调用记录」，或直接开 `/ui/gateways/{id}/calls`。
顶部的状态分面可以一键切到 `ERROR` / `TIMEOUT`；点某一行展开看入参和返回；
点 `trace_id` 按那条链路筛。

**命令行**：

```bash
# 最近 20 次
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records'

# 只看失败的
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records?status=ERROR'

# 按子 MCP 前缀筛一组工具（toolName 是包含匹配）
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records?toolName=kb_a__'

# 按链路关联（需求 15.4.3）
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records?traceId=<trace-id>'

# 时间范围，ISO-8601 instant，from 含、to 不含
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records?from=2026-08-31T00:00:00Z'

# 单条完整内容（含入参和返回正文）
curl 'http://127.0.0.1:8080/api/gateways/<网关ID>/call-records/<callId>'
```

**列表接口不返回入参和返回正文** —— 那两个字段各可能接近 1 MiB，装的是知识库内容，
只能按 `callId` 一条一条取。响应里的 `statusCounts` 是各终态的分布，**不受 `status` 参数影响**，
所以可以拿它做分面切换。

**仍需要直接查库的场景**：跨网关的全局统计（接口按网关隔离），或者要做批量清理。
停掉应用后用 H2 console 或 `h2` jar 连 `./data/mcp-gateway.mv.db`：

```sql
-- 跨网关的失败分布
SELECT gateway_id, error_code, COUNT(*) FROM tool_call_record
WHERE status <> 'SUCCESS' GROUP BY gateway_id, error_code ORDER BY 3 DESC;

-- 库被撑大时按时间清理（目前没有内置的保留策略）
DELETE FROM tool_call_record WHERE started_at < '2026-06-01';
```

字段要点：

- `status`：`STARTED` → `SUCCESS` / `ERROR` / `TIMEOUT`，两阶段写入
- `downstream_mcp_id` 和 `original_tool_name` 为空 = 未知工具或停用工具的调用（无法确定目标）
- `request_json` / `response_json` **不截断**，体积上界由请求/响应大小限制兜住
- `trace_id` 优先取上游的 `traceparent`（只取中间的 trace-id 段），其次 `X-Trace-Id`、`X-Request-Id`、`X-Correlation-Id`，都没有才生成；上游值会做字符过滤和限长

**打点绝不影响调用结果** —— 每次写入用 `REQUIRES_NEW` 开独立事务并吞掉自身异常，只留错误日志和 `mcp.gateway.call.record.failures` 指标。

进程异常退出遗留的 `STARTED` 记录，**下次启动时会被自动标记为 `ERROR`**。

### 9.3 令牌轮换

```bash
curl -X POST http://127.0.0.1:8080/api/gateways/<ID>/access-token/rotate
```

响应里的新令牌是唯一一次。旧令牌**立即失效**，需要同时更新所有已接入 Agent 的配置。数据模型只有一个 `access_token_hash`，做不到双令牌并存。

### 9.4 常见问题

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 启动即退出，日志有 `MCP_GATEWAY_MASTER_KEY ...` | 主密钥缺失 / 非 Base64 / 长度不是 32 字节 | 重新生成，注意是解码后 32 **字节** |
| Agent 报 401 | 令牌错或没带 | 检查 `Authorization: Bearer <token>`；令牌丢了只能轮换 |
| Agent 报 404 | slug 不对，或路径多了段 | 路径必须是 `/mcp/{slug}` 恰好一段 |
| Agent 报 403 | 带了 `Origin` 但不在允许列表 | 配 `MCP_GATEWAY_ALLOWED_ORIGINS`，或去掉 `Origin` 头 |
| `tools/list` 是空的 | 子 MCP 全部同步失败，或工具都被停用了 | 看详情页的 `syncStatus` / `lastSyncError`；点「测试并同步」 |
| 接入 JSON 里的 URL 是 `127.0.0.1`，Agent 连不上 | `MCP_GATEWAY_BASE_URL` 没配对 | 设成 Agent 实际能访问到的地址后重启 |
| 编辑子 MCP 后下游全部 401 | 把遮罩值 `******` 当 headers 提交回去了 | 重新提交真实 headers，或不传 `headers` 字段 |
| 同步报 `INVALID_TOOL_NAME` | `子MCP名__原工具名` 超 128 字符或含非法字符 | 把子 MCP 名字改短 |
| 导入报 `UNSUPPORTED_TRANSPORT` | 配置是 stdio，或 `type` 不是 `streamable-http` | 网关只支持 streamable-http |
| 下游有 302 重定向，请求失败 | 下游客户端**不跟随重定向**（刻意的安全取舍） | 把子 MCP URL 直接配成最终地址 |
| 改了 slug 后 Agent 断连 | slug 变了 MCP URL 就变了 | 更新 Agent 配置里的 URL |

### 9.5 日志

`com.mcpgateway` 默认 `INFO`。排障时：

```bash
java -jar app.jar --logging.level.com.mcpgateway=DEBUG
```

日志里的 header 值**一律遮罩**为 `******`，不做敏感名白名单；令牌只记录前 4 位。构建期的 `SecurityInvariantsTest` 会扫描所有 `log.*` 调用，防止有人不小心把原始 header 写进日志。

---

## 10. 错误码速查

错误码是对外契约的一部分：**只能新增，不能改名或改含义。**

| 错误码 | HTTP | 含义 | 常见触发 |
| --- | --- | --- | --- |
| `INVALID_MCP_CONFIG` | 400 | 子 MCP JSON 格式错误 | 缺 `mcpServers` / 缺 `url` / 名称非法 / URL 含 user-info |
| `MCP_SERVER_LIMIT_EXCEEDED` | 400 | 子 MCP 数量超上限 | 一个网关已有 3 个 |
| `UNSUPPORTED_TRANSPORT` | 400 | 不支持的传输类型 | stdio 配置，或 `type` 不是 `streamable-http` |
| `DOWNSTREAM_INIT_FAILED` | 502 | 子 MCP 初始化失败 | 下游连不上 / initialize 被拒 |
| `DOWNSTREAM_SYNC_FAILED` | 502 | 工具同步失败 | `tools/list` 报错 / 下游返回重名工具 |
| `INVALID_TOOL_NAME` | 400 | 聚合工具名不合法或超长 | `子MCP名__原工具名` > 128 字符 |
| `TOOL_NOT_FOUND` | 404 | 工具不存在 | 聚合名拼错 |
| `TOOL_DISABLED` | 404 | 工具已停用 | 只用于管理 API 和打点记录 |
| `INVALID_TOOL_ARGUMENTS` | 400 | 参数不满足 Schema | |
| `DOWNSTREAM_TIMEOUT` | 504 | 下游调用超时 | 超过 `call-timeout`（默认 30s）|
| `DOWNSTREAM_ERROR` | 502 | 下游返回错误 | |
| `INTERNAL_ERROR` | 500 | 网关内部错误 | 看服务端日志 |
| `INVALID_REQUEST` | 400 | 请求体或参数不合法 | 字段校验失败 / 请求体不是合法 JSON / 方法不支持（405）|
| `ENDPOINT_NOT_FOUND` | 404 | 路径不存在 | |
| `GATEWAY_NOT_FOUND` | 404 | 网关不存在 | |
| `DUPLICATE_GATEWAY_SLUG` | 409 | slug 已被占用 | |
| `DUPLICATE_DOWNSTREAM_NAME` | 409 | 网关内子 MCP 名称重复 | |
| `DOWNSTREAM_NOT_FOUND` | 404 | 子 MCP 不存在 | |
| `UNAUTHORIZED` | 401 | 访问令牌缺失或不正确 | |
| `PAYLOAD_TOO_LARGE` | 413 | 请求体或下游响应体超上限 | 默认各 1 MiB |

---

## 11. 边界与已知限制

### 11.1 管理端没有任何认证

这是需求设定的边界（不含权限系统），不是实现缺陷，但部署时必须当真：

- 默认只监听 `127.0.0.1`
- 容器部署会破坏这一条，所以 compose 只把端口发布到宿主机回环
- actuator 只放开 `/actuator/health`，`show-details: never`

### 11.2 管理 API 没有 CSRF 令牌

同源、无登录、无会话，因此没有传统意义的 CSRF 令牌可放。当前挡住跨站请求的是两点：

1. 所有管理接口**只接受 `application/json`** —— 跨站 `<form>` 只能提交 `application/x-www-form-urlencoded` 等简单类型，请求体解析会失败
2. **没有配置任何 CORS 响应头** —— 跨站 `fetch` 的预检拿不到许可

**这层保护很容易被无意破坏。** 给任何接口加 `@CrossOrigin`、注册 `addCorsMappings`、或让接口接受表单编码，都会打开缺口。`SecurityInvariantsTest.noCorsIsEnabled` 会在构建期拦住前两种。

### 11.3 其他限制

- **令牌轮换没有过渡期** —— 数据模型只有一个哈希，平滑轮换需要改表结构
- **调用记录含知识库内容** —— `request_json` / `response_json` 原样保存不截断。
  查询接口和页面已经有了，但**没有任何清理或归档策略**：记录只在删除网关时级联删除，
  长期运行会持续把库撑大，保留期需要自行处理
- **不跟随 HTTP 重定向** —— 比需求更保守，避免一个 302 把请求连同凭证带去未经校验的主机
- **单机单进程** —— H2 文件库，不支持多实例

---

## 12. 开发与测试

```bash
mvn verify          # 前端构建 + 全部测试 + 覆盖率门禁 + 依赖版本检查
mvn spring-boot:run # 本地启动
mvn -DskipTests -Dfrontend.test.skip=true package
```

`mvn verify` 的四道门禁：

- `maven-enforcer-plugin` —— Java 21+、Maven 3.9+、禁止 SNAPSHOT 依赖、禁止重复依赖声明
- `jacoco:check` —— 指令覆盖率 ≥ 80%、分支覆盖率 ≥ 70%
- `SecurityInvariantsTest` —— 扫描硬编码密钥、CORS 放开、未遮罩的 header 日志
- `npm run test` —— 前端 Vitest 用例。JaCoCo 只看 Java 字节码，视图逻辑迁到 Vue 之后
  就掉出了那道门禁，这一道是补上的

### 前端构建

管理前端在 `frontend/`（Vite + Vue 3 + TypeScript），产物写进
`src/main/resources/static/app/` 后随 jar 一起发布 —— **前后端是一个部署单元**，
不做独立部署，理由见 [README 的管理界面一节](README.md#管理界面)。

`frontend-maven-plugin` 会按 `pom.xml` 里固定的 `node.version` 自己下载一份 Node
到 `target/` 下，宿主机不用预装。但**首次构建需要能访问 nodejs.org 和 npm registry**，
完全离线的环境要预置镜像源。

只改前端时不必走 Maven：

```bash
cd frontend
npm run dev     # :5173，/api 通过 proxy 转给 :8080（需要另起 mvn spring-boot:run）
npm run test
npm run build   # 产物写进 src/main/resources/static/app/
```

跳过前端：

| 参数 | 效果 |
| --- | --- |
| `-Dfrontend.skip=true` | 跳过 Node 安装、`npm ci`、前端构建和前端测试 |
| `-Dfrontend.test.skip=true` | 只跳过前端测试 |

注意 `-DskipTests` **管不到前端** —— 那是 surefire 的参数。两个都要跳就都得给，
Dockerfile 里就是这么写的。

另外：页面测试依赖 `src/main/resources/static/app/` 下的产物（`/ui/gateways` 转发到那里）。
全新克隆上带 `-Dfrontend.skip=true` 会让它们失败，因为产物根本还没生成过。

> **Windows 上先用 Ctrl+C 正常关掉 dev server，再跑 `mvn verify`。**
>
> `npm run dev` 会拉起 vite 和常驻的 `esbuild.exe` 两个子进程，vite 进程还加载着
> rollup 的原生模块 `rollup.win32-x64-msvc.node`。dev server 被**强杀**（关掉终端、
> 杀父进程）时这些子进程会变成孤儿继续活着，攥着文件不放；而 `npm ci` 要先删掉整个
> `node_modules`，于是失败：
>
> ```
> npm error code EPERM
> npm error syscall unlink
> npm error path ...\node_modules\@rollup\rollup-win32-x64-msvc\rollup.win32-x64-msvc.node
> frontend-maven-plugin ... Process exited with an error: -4048
> ```
>
> `-4048` 就是 Windows 的 EPERM。被锁的具体文件每次可能不同（`esbuild.exe` 或某个
> `.node`），但成因是同一个。清掉残留进程即可：
>
> ```powershell
> Get-CimInstance Win32_Process -Filter "Name='node.exe' OR Name='esbuild.exe'" |
>   Where-Object { $_.CommandLine -like '*mcp-gateway*frontend*' } |
>   ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
> ```
>
> 另一个同类现象是首次 `npm install` 报 `EBUSY`（杀软或索引器锁住刚写下的
> `esbuild.exe`），重试一次通常就过了。

依赖漏洞扫描单独放在 profile 里，**发布前必须跑一次**：

```bash
mvn -Psecurity verify -Dnvd.api.key=<你的 NVD API Key>
```

它需要下载并更新 NVD 数据库，首次很慢且离线环境会失败，所以不放进日常构建。CVSS ≥ 7 会让构建失败。

测试用**内存 H2**，每次从空库跑一遍完整 Flyway 迁移；主密钥由 `TestMasterKey` 每次随机生成，仓库里不留任何密钥字面量。

### 代码结构

| 包 | 内容 |
| --- | --- |
| `api` / `api.dto` | 统一响应结构、全局异常出口、管理 API 的请求与响应模型 |
| `error` | 稳定错误码枚举与业务异常 |
| `config` | 部署配置绑定 |
| `security` | AES-GCM 加解密、子 MCP header 编解码、访问令牌、遮罩工具 |
| `domain` / `repository` | 四张表的领域记录、枚举与 `JdbcClient` 数据访问 |
| `lifecycle` | 启动时把遗留 `STARTED` 调用记录标记为 `ERROR` |
| `service` | 网关与子 MCP 的业务逻辑、导入校验、派生状态计算、工具启停 |
| `downstream` | 下游 MCP 客户端、工具同步与快照合并、下游错误码映射 |
| `mcpserver` | 对 Agent 暴露的 MCP 端点：slug 分发、令牌与 Origin 校验、`tools/list` 与 `tools/call` 路由 |
| `recording` | 调用打点：两阶段写入、脱敏、指标 |
| `service.CallRecordService` | 调用记录查询：按网关隔离、分页、状态分面（需求 FR-06.5） |
| `web` | 管理前端入口路由（把 /ui/** 转发给 SPA 空壳）；界面本身在 `frontend/` |

### 改动时的复核点

涉及以下位置时，重新走一遍 [SECURITY.md](SECURITY.md)：

- `DownstreamHeaderCodec` / `AesGcmCipher` —— 凭证的加解密路径
- `GatewayMcpHandler` / `DownstreamErrorMapper` —— 返回给 Agent 的错误文案，任何一条泄漏了下游细节都是安全问题
- `GlobalExceptionHandler` —— 兜底分支很容易把不该暴露的异常内容带出去
- `application.yml` 的 `server.address`、`management.endpoints`、`mcp-gateway.security`
- 任何新增的 `@RestController` —— 确认没有引入 CORS，且不接受表单编码
