# 安全走查

对照需求文档 §12「安全要求」逐条核对，记录实现位置、验证方式和**尚未闭合的缺口**。

最后更新：2026-09-03（管理端登录）

## §12 逐条核对

| # | 要求 | 状态 | 实现位置 / 验证 |
| --- | --- | --- | --- |
| 12.1 | 禁止硬编码访问令牌、加密密钥或子 MCP 凭证 | ✅ | 主密钥只来自 `MCP_GATEWAY_MASTER_KEY`，缺失时 `AesGcmCipher` 让应用启动失败（实测退出码 1）。`SecurityInvariantsTest.noHardcodedSecrets` 扫描源码与配置 |
| 12.2 | 子 MCP headers 用主密钥 AES-GCM 加密后落库 | ✅ | `AesGcmCipher` + `DownstreamHeaderCodec`。实测 H2 文件里 grep 不到明文凭证 |
| 12.3 | 网关访问令牌只保存不可逆哈希 | ✅ | `AccessTokenService` 存 SHA-256，常量时间比对；明文只在创建和轮换的响应里出现一次 |
| 12.4 | 配置查询接口只返回 header 名称及遮罩值 | ✅ | `DownstreamHeaderCodec.maskedView`，**所有** header 值一律遮罩，不做敏感名白名单 |
| 12.5 | 日志、调用记录、异常过滤凭证 | ✅ | `SensitiveDataMasker.describeForLog`；`SecurityInvariantsTest.headersAreNeverLoggedRaw` 扫描所有 `log.*` 调用 |
| 12.6 | 校验 Origin；默认只监听 localhost | ✅ | `OriginValidator` 挂在 SDK transport 的 `securityValidator` 上；`server.address` 默认 `127.0.0.1`（实测确认） |
| 12.7 | 子 MCP URL 只允许 http/https，禁止 user-info；重定向后重新校验 | ✅（更严格） | `DownstreamUrlValidator`。重定向一项采取了更保守的做法：下游客户端**完全不跟随重定向** |
| 12.8 | 限制管理端的访问 | ✅ | 单账号会话登录，凭证只来自环境变量。`SecurityConfig` 两条过滤器链；`ApiAuthorizationInvariantsTest` 逐个端点实打实地探一遍，`SecurityInvariantsTest` 守住口令无默认值、Cookie 加固、不启用 formLogin、只有 Agent 链能关 CSRF |
| 12.9 | 请求体与下游响应体大小上限 | ✅ | `transport.maxRequestSize` 与客户端 `maxResponseSize`，默认各 1 MiB，可配 |

## 已知风险与缓解

### 1. 单账号登录，改口令要重启

管理端是单账号身份校验，**不是**用户体系 —— 需求 3.2 不含权限系统，这里没有角色、没有授权、
没有第二个账号。凭证只来自环境变量：

| 变量 | 必填 | 默认 | 说明 |
| --- | --- | --- | --- |
| `MCP_GATEWAY_ADMIN_USERNAME` | 否 | `admin` | |
| `MCP_GATEWAY_ADMIN_PASSWORD` | **是** | 无 | 缺失或短于 12 位时**启动失败**（`AdminAccount`） |
| `MCP_GATEWAY_COOKIE_SECURE` | 否 | `false` | 放在做了 TLS 的反向代理后面时**必须**设为 `true` |

与主密钥同样的取舍：没有默认值、没有首启向导、没有找回密码通道，代价是**改口令要重启**。
启动时口令被 BCrypt 哈希一次进内存，明文不落库、不进日志。

仍然成立的几条边界：

- 默认只监听 `127.0.0.1`。有了登录之后绑到内网地址成为可选项，但纵深防御不因为多了一道
  就撤掉前一道，`docker-compose.yml` 里端口仍只发布到宿主机回环。
- actuator 只放开 `/actuator/health`，且 `show-details: never`。其余路径落在
  `SecurityConfig` 末尾的 `anyRequest().denyAll()` 上，返回 401。
- 登录失败按来源 IP 限速：连续 5 次后锁 5 分钟，**锁定期间不比对口令**（否则能通过响应时间
  区分"锁着且口令对"和"锁着且口令不对"）。状态在内存里，重启清空。
  注意反向代理后面所有请求的来源 IP 都是代理，此时限速会退化成全局的 ——
  要按真实客户端计数就得配 `server.forward-headers-strategy`，而那是一个信任决策。

### 1b. 登录不得波及 Agent 链路

`/mcp/**` 走 `SecurityConfig` 里**单独一条** `@Order(1)` 的过滤器链：无状态、不种 Cookie、
不查 CSRF 令牌，令牌校验仍由 `GatewayMcpDispatcherServlet` 自己做。

这条链不是可有可无的整洁：Spring Security 的过滤器映射在 `/*` 上，会连 Agent 流量一起接管。
更隐蔽的是它的 matcher **必须显式构造**，不能写成 `securityMatcher("/mcp/**")` ——
那个字符串重载会生成按 *servlet 内相对路径* 匹配的 `MvcRequestMatcher`，而 `/mcp/*` 是自己
注册的一个 servlet，`/mcp/foo` 在它眼里是 `/foo`，匹配不上。结果是 Agent 流量静悄悄落到管理端
那条链上撞在 CSRF 校验返回 403，没有任何报错提示。`AgentEndToEndTest` 覆盖了这一整条链路，
其中 `mcpEndpointStaysStatelessForAgents` 专门验响应里没有 `Set-Cookie`。

### 2. CSRF：加了会话 Cookie 才有的攻击面

**登录引入了这个项目此前不存在的 CSRF 面。** 在此之前的论证是"没有登录所以没有身份可借用"，
那句话从有会话 Cookie 的那一刻起失效：浏览器会自动带上它，跨站发起的请求就有了身份。

现在是四层，任何一层单独失效都还有下一层：

1. **会话 Cookie 是 `SameSite=Strict`**。管理端没有任何"从外站跳进来"的正当场景，
   Strict 无副作用，也是主力。
2. **写请求校验 CSRF 令牌**。`CookieCsrfTokenRepository`（`XSRF-TOKEN`）+ 前端回填
   `X-XSRF-TOKEN`，登录成功时轮换。
3. **接口只接受 `application/json`**。跨站 `<form>` 提交只能用
   `application/x-www-form-urlencoded` 等简单类型，会被 415 挡在外面。
4. **没有配置任何 CORS 响应头**，跨站 `fetch` 的预检请求拿不到许可。

CSRF 那两个默认值都必须改，少改一个就是一个只在特定时序下复现的 403：
Spring Security 6 默认的 `XorCsrfTokenRequestAttributeHandler`（BREACH 防护）会让 Cookie
里的值与服务端期望值对不上；`csrfRequestAttributeName` 不设为 `null` 则令牌是惰性的 ——
没人读就不生成，Cookie 也就不下发，登录后轮换那一步尤其致命（旧的已删、新的没发）。
`CsrfAuthenticationStrategy` 必须和过滤器链共用同一个处理器。

**这些保护很容易被无意破坏**：给任何接口加 `@CrossOrigin`、注册 `addCorsMappings`、
让接口接受表单编码、或把 `.csrf(...disable)` 从 Agent 链复制到管理端链，都会打开缺口。
`SecurityInvariantsTest` 里的 `noCorsIsEnabled`、`loginDoesNotFallBackToFormOrBasic`、
`csrfIsDisabledOnlyForTheAgentChain`、`sessionCookieStaysHardened` 在构建期各守一条。

**管理前端仍然不做独立部署。** 前端（`frontend/`，Vite + Vue）的产物打进
`src/main/resources/static/app/`，随 jar 一起发布，与 API 同源；开发期由 Vite 的
`server.proxy` 转发 `/api`，浏览器看到的同样是同源请求。两种形态都不需要 CORS。

拆成独立部署单元就必须放开 CORS，上面第 4 点直接消失，第 1 点也跟着失效 ——
跨源请求根本带不上 `SameSite=Strict` 的 Cookie，那意味着还得把会话改成别的载体。
现在有了登录，这条路**技术上可行**了，但它是一个独立决策，不是登录功能顺手带来的结果。

### 3. 令牌轮换没有过渡期

`mcp_gateway` 只有一个 `access_token_hash`，轮换即刻让在用的 Agent 断连。
需求 FR-05.3 没有要求双令牌并存，这里按需求实现。若运维上需要平滑轮换，需要改数据模型。

### 4. 调用记录含知识库返回的内容

`request_json` / `response_json` 按 FR-06.4 原样保存，其中可能有敏感业务内容。
数据库文件必须按部署要求保护（compose 里放在具名卷中）。**目前没有任何清理或归档策略** ——
记录只在删除网关时被级联删掉，长期运行会持续增长。

**这些内容有 HTTP 出口。** `GET /api/gateways/{id}/call-records/{callId}` 会返回
完整的入参和返回正文。登录挡住了匿名访问，但这仍是整个管理 API 里唯一会吐出业务正文的地方 ——
一把被偷走的会话在这里能捞到的东西远多于别处。两处收敛照旧：

- **列表接口不带正文**。`GET .../call-records` 只返回摘要（工具名、状态、耗时、错误码），
  正文只能按 `callId` 一条一条取。这样一次请求捞不走成批的业务内容，
  由 `CallRecordApiTest.listNeverCarriesPayloads` 在构建期把关。
- **按网关隔离**。列表永远带 `gateway_id` 条件，取单条时再校验归属；拿别的网关的 `callId`
  来查会得到和"不存在"完全一致的 `CALL_RECORD_NOT_FOUND`。`callId` 是 UUID 猜不到，
  但"猜不到"不是访问控制。

登录**不是**放宽这两条的理由：单账号系统里所有会话都是同一个身份，隔离挡的是误操作和
凭证泄漏之后的横向扩散，不是越权。

反过来，记录里**不会**有凭证：需求 FR-06.3 规定任何 header、网关访问令牌和子 MCP 凭证
都不得进入调用记录，打点服务本身就拿不到它们；`error_message` 也是已脱敏的摘要。

### 5. 依赖漏洞扫描不在默认构建里

`mvn -Psecurity verify -Dnvd.api.key=<KEY>` 运行 OWASP dependency-check，CVSS ≥ 7 会让构建失败。
放进 profile 是因为它需要下载并更新 NVD 数据库，首次很慢且离线环境直接失败 ——
让日常构建依赖外部数据源不是好主意。**发布前必须跑一次。**

## 构建期的安全与质量门禁

`mvn verify` 会执行：

- `maven-enforcer-plugin`：Java 21+、Maven 3.9+、禁止 SNAPSHOT 依赖（需求 13.3）、禁止重复依赖声明
- `jacoco:check`：指令覆盖率 ≥ 80%、分支覆盖率 ≥ 70%（需求 15.5）
- `SecurityInvariantsTest`：上表中标注了"扫描"的那几条
- `ApiAuthorizationInvariantsTest`：把所有 `/api` 端点从 Spring MVC 的处理器映射里枚举出来
  逐个真打一遍，除登录和会话查询外必须一律 401。守的是**以后新加的控制器** ——
  漏配的端点不会让任何功能测试变红

## 复核时的重点

改动涉及以下位置时，请重新走一遍本文档：

- `DownstreamHeaderCodec` / `AesGcmCipher`：凭证的加解密路径
- `GatewayMcpHandler` / `DownstreamErrorMapper`：返回给 Agent 的错误文案，任何一条泄漏了下游细节都是安全问题
- `GlobalExceptionHandler`：兜底分支很容易把不该暴露的异常内容带出去
- `SecurityConfig`：两条过滤器链的边界、白名单、CSRF 处理器；改这里必须重跑
  `AgentEndToEndTest`，它是"没碰坏 Agent 链路"的唯一证明
- `AdminAccount` / `LoginThrottle`：凭证校验与限速
- `AuthController`：会话固定防护和 SecurityContext 保存这两步是手工接的，删掉不会有编译错误
- `application.yml` 的 `server.address`、`server.servlet.session.cookie`、
  `management.endpoints`、`mcp-gateway.security`
- 任何新增的 `@RestController`：确认没有引入 CORS、不接受表单编码，且落在
  `/api` 前缀下（否则 `ApiAuthorizationInvariantsTest` 覆盖不到它）
- `frontend/vite.config.ts`：`build.outDir` 必须指向 `src/main/resources/static/`
  之下——产物一旦离开 jar，前端就不再与 API 同源，第 2 点的保护随之失效
- `frontend/src/api/client.ts`：请求必须始终带 `Content-Type: application/json` 和
  写请求的 `X-XSRF-TOKEN`，且不得改成绝对 URL 指向另一个源
- `frontend/src/views/LoginView.vue` 的 `safeRedirect`：`redirect` 参数来自地址栏，
  只接受站内绝对路径，否则登录页就成了开放重定向
