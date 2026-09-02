# MCP 聚合网关

**使用与运维手册见 [USAGE.md](USAGE.md)。** 本文档记录的是设计取舍和实现要点。

本仓库只包含 gateway 模块。需求文档与 W0 技术探针（probe 工程）保留在上游工作区，未随本仓库发布。

当前进度：W1（工程骨架）、W2（数据层与密钥）、W3（网关与子 MCP 配置 API）、W4（下游客户端与同步引擎）、W5（对外 MCP Server 与调用路由）、W6（调用打点）、W7（管理前端）、W8（安全收口、验收测试与容器化）完成。MVP 功能范围已全部实现。

目录布局：

```
gateway/
  src/main/java/com/mcpgateway/     应用代码
  src/main/resources/               配置、Flyway 迁移、前端产物与静态资源
  src/test/java/                    单元与验收测试
  frontend/                         管理前端（Vite + Vue 3 + TypeScript）
  Dockerfile / docker-compose.yml   容器化部署
```

管理前端是 Vue 单页应用，产物由 Vite 打进 `src/main/resources/static/app/`，随 jar 一起发布。
取舍见下面的[管理界面](#管理界面)一节。

> 仓库路径必须保持纯 ASCII。在 `sun.jnu.encoding=GBK` 的 Windows 上，含中文的绝对路径
> 会在传给 forked JVM 的命令行参数里被破坏（JaCoCo 的 `-javaagent` destfile 首当其冲），
> 且无法通过 `MAVEN_OPTS` 修正 —— 该属性在 JVM 启动时由系统代码页定死。

## 运行前置

网关拒绝在没有主密钥的情况下启动 —— 这是刻意的，避免子 MCP 凭证退化成明文落库（需求 12.1 / 12.2）。

生成一把 32 字节主密钥：

```bash
openssl rand -base64 32
```

PowerShell 下：

```powershell
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
```

## 环境变量

| 变量 | 必填 | 默认值 | 说明 |
| --- | --- | --- | --- |
| `MCP_GATEWAY_MASTER_KEY` | 是 | 无 | Base64 编码的 32 字节 AES 主密钥。缺失或长度不对时启动失败 |
| `MCP_GATEWAY_BASE_URL` | 建议 | `http://127.0.0.1:8080` | Agent 接入 JSON 里的地址，只能来自配置，不从请求头拼接（FR-05.1） |
| `MCP_GATEWAY_BIND_ADDRESS` | 否 | `127.0.0.1` | 管理端无登录，默认只监听 localhost（需求 12.6 / 4.3） |
| `MCP_GATEWAY_PORT` | 否 | `8080` | |
| `MCP_GATEWAY_DB_PATH` | 否 | `./data/mcp-gateway` | H2 文件库路径。库里含知识库返回内容，需按部署要求保护（FR-06.4） |
| `MCP_GATEWAY_ALLOWED_ORIGINS` | 否 | 空 | 逗号分隔。内网部署时显式配置允许来源 |

## 构建与测试

```bash
mvn verify        # 前端构建 + 全部测试 + 覆盖率门禁 + 依赖版本检查
mvn spring-boot:run
```

`mvn verify` 会执行四道门禁：

- `maven-enforcer-plugin`：Java 21+、禁止 SNAPSHOT 依赖（需求 13.3）
- `jacoco:check`：指令覆盖率 ≥ 80%、分支覆盖率 ≥ 70%（需求 15.5）
- `SecurityInvariantsTest`：扫描硬编码密钥、CORS 放开、未遮罩的 header 日志等
- `npm run test`：前端的 Vitest 用例。视图逻辑搬到 Vue 之后就掉出了 JaCoCo 的
  覆盖范围（那道门禁只看 Java 字节码），这一道是补上的

**构建需要 Node。** `frontend-maven-plugin` 会按 `pom.xml` 里固定的 `node.version`
自动下载一份 Node 到 `target/` 下，不用宿主机预装，但**首次构建需要能访问
nodejs.org 和 npm registry**。完全离线的环境要预置镜像源。

前端可以单独跳过：

```bash
mvn -Dfrontend.skip=true verify        # 复用上次构建好的前端产物
mvn -Dfrontend.test.skip=true verify   # 只跳过前端测试
```

`-DskipTests` **管不到前端** —— 那是 surefire 的参数。镜像构建里两个都给了。

只改前端时不必走 Maven：

```bash
cd frontend
npm run dev     # 开发服务器，:5173，API 通过 proxy 转给 :8080
npm run test
npm run build   # 产物写进 src/main/resources/static/app/
```

`npm run dev` 之外还要另起一个 `mvn spring-boot:run` —— 前端只代理 API，不自带后端。

依赖漏洞扫描单独放在 profile 里，**发布前必须跑一次**：

```bash
mvn -Psecurity verify -Dnvd.api.key=<你的 NVD API Key>
```

它需要下载并更新 NVD 数据库，首次很慢且离线环境会失败，所以不放进日常构建。

## 持续集成与发版

`.github/workflows/build.yml`。推送到 `main` / `dev`、提 PR、打 `v*` 标签都会跑一遍
完整的 `mvn verify`（四道门禁全在里面），jar 作为 artifact 上传，保留 30 天。

跑的是 `verify` 不是 `package` —— 后者会跳过全部门禁。CI 里也**不能**带
`-Dfrontend.skip=true`：前端产物目录在 `.gitignore` 里，CI 是全新克隆，跳过前端构建
会让页面测试直接失败。Node 由 `frontend-maven-plugin` 自己下载，runner 不用预装。

`-Psecurity` 不在 CI 里跑（要 NVD API Key，首次要下载整个漏洞库），仍按上面的要求
在发布前手工跑一次。

发版按这个顺序，**先改版本再打标签**：

```bash
mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false   # 去掉 -SNAPSHOT
git commit -am "发布 0.1.0" && git push
git tag v0.1.0 && git push origin v0.1.0
```

标签构建通过后，会用**同一份已过门禁的 jar**（不重新构建）建一个 GitHub Release，
发布说明自动生成。带连字符的标签（`v0.2.0-rc1`）自动标成预发布。

版本必须对得上：release 那步会校验 jar 版本与标签一致，不一致直接失败。这道闸门是
必要的 —— `enforcer` 的 `requireReleaseDeps` 只管依赖和父 pom，管不到项目自身版本，
带着 `-SNAPSHOT` 打标签构建照样是绿的，发出去的资产却名不副实。

## 容器部署

```bash
export MCP_GATEWAY_MASTER_KEY=$(openssl rand -base64 32)
export MCP_GATEWAY_BASE_URL=http://<Agent 能访问到的地址>:8080
docker compose up -d --build
```

两个容器化特有的坑：

- 容器里必须监听 `0.0.0.0`，这就**放弃了"默认只监听 localhost"那道保护**。compose 因此把端口
  只发布到宿主机回环 `127.0.0.1:8080:8080`。管理端没有登录，改成 `0.0.0.0` 等于把它交给整个网络。
- `MCP_GATEWAY_BASE_URL` 必须是 Agent 实际能访问到的地址，不能是容器内部视角的 `127.0.0.1` ——
  它会原样写进接入 JSON（需求 FR-05.1）。

安全走查见 [SECURITY.md](SECURITY.md)。

测试用内存 H2，每次从空库跑一遍完整 Flyway 迁移；主密钥由 `TestMasterKey` 每次随机生成，仓库里不留任何密钥字面量。

## 代码结构

| 包 | 内容 |
| --- | --- |
| `api` | 统一响应结构 `{success, data, error}` 与全局异常出口 |
| `error` | 稳定错误码枚举（需求 §11）与业务异常 |
| `config` | 部署配置绑定 |
| `security` | AES-GCM 加解密、子 MCP header 编解码、访问令牌、遮罩工具 |
| `domain` | 四张表对应的领域记录与枚举 |
| `repository` | Spring JDBC `JdbcClient` 数据访问 |
| `lifecycle` | 启动时把遗留 `STARTED` 调用记录标记为 `ERROR`（需求 13.2） |
| `service` | 网关与子 MCP 的业务逻辑、`mcpServers` 导入校验、派生状态计算、工具启停 |
| `downstream` | 下游 MCP 客户端、工具同步与快照合并、下游错误码映射 |
| `mcpserver` | 对 Agent 暴露的 MCP 端点：slug 分发、令牌与 Origin 校验、tools/list 与 tools/call 路由 |
| `recording` | 调用打点：两阶段写入、脱敏、指标 |
| `web` | 管理前端的入口路由（把 /ui/** 转发给 SPA 空壳） |
| `api.dto` | 管理 API 的请求与响应模型 |

## 管理 API

| 方法 | 路径 | 状态 |
| --- | --- | --- |
| `GET` | `/api/gateways` | 已实现 |
| `POST` | `/api/gateways` | 已实现，响应含一次性明文令牌 |
| `GET` | `/api/gateways/{id}` | 已实现 |
| `PUT` | `/api/gateways/{id}` | 已实现 |
| `DELETE` | `/api/gateways/{id}` | 已实现，级联删除 |
| `POST` | `/api/gateways/{id}/mcp-servers/import` | 已实现 |
| `PUT` | `/api/gateways/{id}/mcp-servers/{serverId}` | 已实现 |
| `DELETE` | `/api/gateways/{id}/mcp-servers/{serverId}` | 已实现 |
| `GET` | `/api/gateways/{id}/agent-config` | 已实现 |
| `POST` | `/api/gateways/{id}/access-token/rotate` | 已实现 |
| `POST` | `/api/gateways/{id}/mcp-servers/{serverId}/sync` | 已实现，失败也返回 200，细节在 body |
| `PATCH` | `/api/gateways/{id}/tools/{toolId}` | 已实现，PATCH 语义 |
| `GET` | `/api/gateways/{id}/call-records` | 已实现，分页与筛选 |
| `GET` | `/api/gateways/{id}/call-records/{callId}` | 已实现，含入参与返回正文 |

两处 PATCH 语义需要注意：

- 编辑子 MCP 时，请求体里**不传** `headers` 表示保持原有凭证不变，传空对象 `{}` 才是清空。
  前端拿到的 `headers` 是遮罩值，原样提交回来会把真凭证覆盖掉。
- 修改工具时，不传 `customDescription` 表示不改；传 `null` 或空白串表示清除并回退到原始描述。

## 管理界面

浏览器打开 `{baseUrl}/` 即可，会跳到网关列表页。前两个页面对应需求 §10，第三个是 FR-06.5 的查询界面：

- **列表页** `/ui/gateways`：名称、slug、状态、子 MCP 数量、工具数量、更新时间，以及创建和删除。
- **详情页** `/ui/gateways/{id}`：基本信息 / 子 MCP 配置 / 聚合工具 / Agent 接入 四段。
- **调用记录页** `/ui/gateways/{id}/calls`：按工具、子 MCP、状态、trace_id 和时间筛选，展开看入参与返回。

### 实现方式：Vite + Vue 3 + TypeScript

前端在 `frontend/`，三个页面同属一个 Vue 单页应用：

| 页面 | 组件 |
| --- | --- |
| 列表页 `/ui/gateways` | `frontend/src/views/GatewayListView.vue` |
| 详情页 `/ui/gateways/{id}` | `frontend/src/views/GatewayDetailView.vue` |
| 调用记录页 `/ui/gateways/{id}/calls` | `frontend/src/views/CallRecordsView.vue` |

服务端只把 `/ui/**` 转发到 SPA 的入口文档（`GatewayPageController`），所有数据走 `/api` 下的
管理接口。Thymeleaf 已经完全移除，模板、`app.js` 和本地 Bootstrap 一并删掉。

**产物同源打进 jar，不做独立部署。** 这是整个迁移最关键的一条约束，理由是安全而不是省事：

管理端没有登录、没有会话、没有 CSRF 令牌。目前挡住跨站请求的只有两点 —— 接口只收
`application/json`，以及**一个 CORS 响应头都没有**。真做成前后端分离部署就必须放开 CORS，
那层保护会直接消失，而且没有任何登录能兜底。所以：

- 开发期 Vite 的 `server.proxy` 把 `/api` 转给后端，浏览器看到的始终是同源
- 生产期 Vite 产物写进 `src/main/resources/static/app/`，随 jar 一起发布

两种形态都不需要 CORS。`SecurityInvariantsTest.noCorsIsEnabled` 会在构建期拦住
`@CrossOrigin`、`addCorsMappings` 和 `Access-Control-Allow`。

**要真做独立部署，得先给管理端做认证** —— 有了凭证校验，CORS 才有得谈。

### 几处刻意的取舍

- **不引用任何外网资源**。部署环境可能是没有外网的内网，CDN 会让界面直接不可用。
  样式和脚本全部随 jar 发布，`UiServingTest` 会验入口文档只引用同源相对路径，
  且页面和脚本里都没有 CDN 域名。
- **暂时没有 UI 组件库**，样式是 `frontend/src/styles/app.css` 里的一份自带样式表。
  三个页面都做完之后再统一选型，避免过早锁死在某一家的组件模型上。
- **编辑子 MCP 时默认不提交 headers**。页面上显示的是遮罩值 `******`，原样提交回去会把真凭证
  覆盖掉，所以要先勾"替换 headers"才会带上这个字段。这条语义由
  `frontend/test/gateway-detail-view.spec.ts` 钉住 —— 它断言的是请求体里**没有这个键**，
  而不是它的值是 undefined。
- **令牌只在创建和轮换后当场显示一次**。它只活在组件的 props 里，页面不刷新，
  也就不存在被冲掉的可能。
- **提示文案一律走文本插值，不拼 HTML**：错误信息里可能带下游返回的内容，拼进 HTML
  就是一个存储型 XSS。全站用 `{{ }}`，不用 `v-html`。
- **变更后只重取数据，不整页刷新**。大多数写接口本来就返回完整的 `GatewayDetailResponse`，
  直接拿返回值替换状态即可；只有同步接口只返回统计，才需要再取一次详情。
- **粘贴的 mcpServers JSON 原样转发**，前端不先绑一层模型。服务端要靠里面有没有
  `command` / `args` / `env` 判定 stdio 配置并报 `UNSUPPORTED_TRANSPORT`；
  前端过一道模型会把这些未知字段悄悄吃掉，用户就会看到一个"导入成功"却完全不是他想要的结果。

## Agent 接入

每个网关的 MCP 地址是 `{baseUrl}/mcp/{slug}`，Streamable HTTP，需要 `Authorization: Bearer <令牌>`。
接入 JSON 从 `GET /api/gateways/{id}/agent-config` 取。

实现要点：

- 所有网关共用一个挂在 `/mcp/*` 的分发器，按 slug **精确匹配**后把请求委派给该网关的 SDK transport。
  精确匹配不能省：SDK transport 内部只做 `requestURI.endsWith(messageEndpoint)` 的后缀比较，
  `/mcp/anything/mcp/real-slug` 这类路径能骗过它。
- 网关的 SDK server **不注册任何工具**。工具目录由网关自己的 handler 每次从数据库快照读取，
  因此启停工具、重新同步、改描述都立即生效，不需要重建 MCP 上下文（需求 6.4.1 / 6.4.8）。
- `initialize`、`ping` 等方法原样交给官方 SDK 处理，网关只接管 `tools/list` 和 `tools/call`。
- 错误码放在 JSON-RPC 错误的 `data.errorCode` 里，`message` 只放已脱敏的短句。
  停用工具和未知工具的对外文案完全一致，不向 Agent 暴露"这个工具存在但被停用了"这类配置状态。
- 下游客户端按子 MCP 缓存复用，避免每次调用多一次 MCP initialize 往返吃掉需求 13.1 的 100 ms 预算。
  缓存有效性用「URL + 加密后 headers」的指纹判断，配置一改自动重建。

## 调用打点

每次 `tools/call` 都会产生一条 `tool_call_record`，两阶段写入：开始时写 `STARTED`，结束时更新
`SUCCESS` / `ERROR` / `TIMEOUT`。

- **没有缺口**：未知工具和停用工具的调用同样留下完整记录。这依赖 W5 的 handler 装饰器 ——
  如果走 SDK 自带的工具注册表，这两种请求根本到不了网关代码，FR-06 就会漏记。
  这类记录的 `downstream_mcp_id` 和 `original_tool_name` 为空，因为无法确定目标。
- **打点失败不影响调用结果**（FR-06.2）：每次写入用 `REQUIRES_NEW` 开独立事务，并把自身异常
  全部吞掉，只留错误日志和 `mcp.gateway.call.record.failures` 指标。
- **脱敏**（FR-06.3）：打点服务只接收工具参数和结果，拿不到任何请求头；错误摘要与返回给 Agent
  的是同一份已脱敏文案。
- `trace_id` 优先取上游的 `traceparent`（只取中间的 trace-id 段）、`X-Trace-Id`、`X-Request-Id`、
  `X-Correlation-Id`，都没有才生成。上游值不可信，会做字符过滤和限长。
- 输入输出按原始 JSON 保存，不截断（FR-06.4）。体积上界由请求体和下游响应体的大小限制兜住。
- 进程异常退出遗留的 `STARTED` 记录，下次启动时被标记为 `ERROR`（需求 13.2）。
- 指标经 Micrometer 暴露；actuator 的 web 端点默认只放开 `health`。

## 调用记录查询

需求 FR-06.5 的查询接口和页面（MVP 阶段是空的，排障只能连库）：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `GET` | `/api/gateways/{id}/call-records` | 分页列表，支持按工具名、子 MCP、状态、trace_id、时间范围筛选 |
| `GET` | `/api/gateways/{id}/call-records/{callId}` | 单条完整内容，**含入参和返回正文** |

界面在 `/ui/gateways/{id}/calls`，从详情页进去。

三处刻意的取舍：

- **列表不返回 `request_json` / `response_json`。** 那两列按 FR-06.4 原样保存不截断，
  单条就可能接近 1 MiB，装的是知识库正文。列表一次几十条把它们带上，等于在一个没有登录的
  接口上成批摊开业务内容。正文只能按 `callId` 一条一条取 ——
  `CallRecordApiTest.listNeverCarriesPayloads` 在构建期守着这条。
- **记录严格按网关隔离。** 列表永远带 `gateway_id` 条件，取单条时再校验归属；
  拿别的网关的 `callId` 来查，得到的是和"不存在"完全一致的 `CALL_RECORD_NOT_FOUND`。
  `callId` 是 UUID 猜不到，但"猜不到"不是访问控制。
- **`statusCounts` 不套用 `status` 筛选**，其余条件照常生效。它是给界面做分面切换用的 ——
  已经筛了 `ERROR` 还只显示 `ERROR` 的数量，就没法拿它跳到别的状态了。

非法的 `status` / `from` / `to` 一律报 `INVALID_REQUEST` 而不是静默忽略：静默忽略会让操作人
以为"筛出来就这些"，而实际上根本没筛。`size` 超过 100 则收敛到 100，响应里的 `size` 字段
说明实际用了多少。

**调用记录目前没有清理或归档策略**，只在删除网关时级联删除。长期运行需要自行处理。

## 同步行为

- 导入子 MCP 后立即同步一次（需求 6.4.2）。配置整批原子落库，同步则逐个尝试、各自记录成败，
  一个下游连不上不影响其他（需求 6.2.9）。
- 同步失败保留上一次成功的快照，只把子 MCP 标记为 `FAILED`；`last_sync_at` 停在上一次成功的时刻，
  因为这个字段的含义是"快照有多新"而不是"上次尝试是什么时候"。
- 重新同步覆盖协议字段（原始描述、Schema、annotations），但保留操作人配置的启停状态和自定义描述。
  这一点由 SQL 结构保证：`updateProtocolFields` 的语句里根本没有那两列。
- 聚合工具名不合法或超过 128 字符时整次同步失败，不静默截断（需求 6.3.5）。
- 出于安全考虑，下游客户端**不跟随 HTTP 重定向**。需求 12.7 要求重定向后重新校验协议，
  MVP 采取更保守的做法，避免一个 302 把请求连同凭证带去未经校验的主机。

数据库迁移在 `src/main/resources/db/migration/`。
