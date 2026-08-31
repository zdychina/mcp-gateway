# 安全走查

对照需求文档 §12「安全要求」逐条核对，记录实现位置、验证方式和**尚未闭合的缺口**。

最后更新：2026-08-31（W8）

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
| 12.8 | 管理端无登录，靠网络边界限制访问 | ⚠️ 部分 | 见下方「已知风险」 |
| 12.9 | 请求体与下游响应体大小上限 | ✅ | `transport.maxRequestSize` 与客户端 `maxResponseSize`，默认各 1 MiB，可配 |

## 已知风险与缓解

### 1. 管理端没有任何认证（需求 3.2 明确不含权限系统）

这是需求设定的边界，不是实现缺陷，但部署时必须当真：

- 默认只监听 `127.0.0.1`。**容器部署会破坏这一条** —— 容器内必须绑 `0.0.0.0` 端口映射才有意义，
  所以 `docker-compose.yml` 里把端口只发布到宿主机回环 `127.0.0.1:8080:8080`。
  改成 `0.0.0.0:8080:8080` 就等于把无认证的管理端交给整个网络。
- actuator 只放开 `/actuator/health`，且 `show-details: never`。

### 2. 管理 API 没有 CSRF 防护

同源、无登录、无会话，因此不存在传统意义的 CSRF 令牌可放。当前挡住跨站请求的是两点：

1. 所有管理接口只接受 `application/json`。跨站 `<form>` 提交只能用
   `application/x-www-form-urlencoded` 等简单类型，请求体解析会失败。
2. 没有配置任何 CORS 响应头，跨站 `fetch` 的预检请求拿不到许可。

**这层保护很容易被无意破坏**：给任何接口加 `@CrossOrigin`、注册 `addCorsMappings`、
或让接口接受表单编码，都会打开缺口。`SecurityInvariantsTest.noCorsIsEnabled` 会在构建期拦住前两种。

### 3. 令牌轮换没有过渡期

`mcp_gateway` 只有一个 `access_token_hash`，轮换即刻让在用的 Agent 断连。
需求 FR-05.3 没有要求双令牌并存，这里按需求实现。若运维上需要平滑轮换，需要改数据模型。

### 4. 调用记录含知识库返回的内容

`request_json` / `response_json` 按 FR-06.4 原样保存，其中可能有敏感业务内容。
数据库文件必须按部署要求保护（compose 里放在具名卷中）。MVP 不提供记录的清理策略（FR-06.5）。

### 5. 依赖漏洞扫描不在默认构建里

`mvn -Psecurity verify -Dnvd.api.key=<KEY>` 运行 OWASP dependency-check，CVSS ≥ 7 会让构建失败。
放进 profile 是因为它需要下载并更新 NVD 数据库，首次很慢且离线环境直接失败 ——
让日常构建依赖外部数据源不是好主意。**发布前必须跑一次。**

## 构建期的安全与质量门禁

`mvn verify` 会执行：

- `maven-enforcer-plugin`：Java 21+、Maven 3.9+、禁止 SNAPSHOT 依赖（需求 13.3）、禁止重复依赖声明
- `jacoco:check`：指令覆盖率 ≥ 80%、分支覆盖率 ≥ 70%（需求 15.5）
- `SecurityInvariantsTest`：上表中标注了"扫描"的那几条

## 复核时的重点

改动涉及以下位置时，请重新走一遍本文档：

- `DownstreamHeaderCodec` / `AesGcmCipher`：凭证的加解密路径
- `GatewayMcpHandler` / `DownstreamErrorMapper`：返回给 Agent 的错误文案，任何一条泄漏了下游细节都是安全问题
- `GlobalExceptionHandler`：兜底分支很容易把不该暴露的异常内容带出去
- `application.yml` 的 `server.address`、`management.endpoints`、`mcp-gateway.security`
- 任何新增的 `@RestController`：确认没有引入 CORS，且不接受表单编码
