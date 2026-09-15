# jar 部署

面向把网关以可执行 jar 跑在一台测试/内网服务器上的场景。容器部署见 [README.md](README.md#容器部署)，
安全走查见 [SECURITY.md](SECURITY.md)。

## 先读这一条

管理端有登录（单账号，凭证来自环境变量），但**它是一个口令，不是一套访问控制体系**：
没有角色、没有多账号、没有审计追责 —— 需求 3.2 不含权限系统。

`MCP_GATEWAY_BIND_ADDRESS` 默认仍是 `127.0.0.1`（需求 12.6 / 4.3）。下面的部署要把它改成
`0.0.0.0`：凡是能访问到这个端口的人，就都到了登录页前面，此后挡着他们的只有那一个口令。
**仍然建议放在可信内网，并用安全组 / iptables 限制来源 IP** —— 纵深防御不因为多了一道就撤掉
前一道。口令至少 8 位，短于这个长度网关直接拒绝启动。

三个平面的保护级别不同，别混为一谈：

| 平面 | 路径 | 鉴权 |
| --- | --- | --- |
| Agent 数据面 | `/mcp/{slug}` | `Authorization: Bearer <网关访问令牌>`，无令牌 401。**不受管理端登录影响** |
| 管理面 | `/api/**` | 会话 Cookie，未登录 401 |
| 前端外壳 | `/ui/**`、`/app/**` | 公开 —— 未登录时要靠它渲染登录页，但拿到空壳也拉不到任何数据 |

**做了 TLS 反代时必须设 `MCP_GATEWAY_COOKIE_SECURE=true`**，否则会话 Cookie 会在明文
连接上也照发不误。反过来，直接明文 HTTP 暴露时它必须保持 `false`，不然 Cookie 根本不下发、
登录进不去。

子 MCP 用的是内网自签证书、且拿不到根证书时，可以设
`MCP_GATEWAY_DOWNSTREAM_INSECURE_SKIP_TLS_VERIFY=true` 关掉下游 TLS 校验 ——
但先读 SECURITY.md 的「下游 TLS 校验」一节：导 CA 根证书是更好的做法，成本也更低。


> **已经部署过旧版本、这次是升级？** 别照着本文从头走一遍 ——
> 升级有它自己的顺序和坑（停服顺序、配置变更、回滚），见 [UPGRADE.md](UPGRADE.md)。

## 一、构建

在**能联网的机器**上打包，不要在目标服务器上打：

```bash
mvn -DskipTests -Dfrontend.test.skip=true package
```

产物：`target/mcp-gateway-1.2.1.jar`，管理前端已随 jar 一起打进去，不需要单独部署。

**要挂在子路径下（`/kbmcp` 这类前缀）的，这一步就必须带上前缀**，后面再补是补不了的：

```bash
mvn -Dvite.base.path=/kbmcp -DskipTests -Dfrontend.test.skip=true clean package
```

漏了这个参数，jar 照样能打出来、服务照样能起来、页面照样返回 200 —— 只是白屏，因为
入口文档里的资源地址不带前缀，浏览器去了同域上别的应用那里。所以**打完先验产物再传**：

```bash
unzip -p target/mcp-gateway-1.2.1.jar BOOT-INF/classes/static/app/index.html | grep -o 'src="[^"]*"'
# 期望 src="/kbmcp/app/assets/index-xxxx.js"
# 出现 src="/app/assets/..." 就是漏了 -Dvite.base.path，重打，别传
```

详见下面的[「挂在子路径下」](#挂在子路径下)。

两点说明：

- `-DskipTests` 只对 surefire 生效，管不到 npm，所以 `-Dfrontend.test.skip=true` 要单独给。
- 构建会下载 Node（`v24.15.0`）并执行 `npm ci`，**需要访问 nodejs.org 和 npm registry**。
  完全离线的机器打不了包。

正式发布前应当先跑一遍完整的 `mvn verify`（含覆盖率门禁与依赖版本检查）。

服务器上只需要 **JRE 21**，不需要 Maven、Node 或 JDK：

```bash
java -version   # 期望 21 及以上
```

## 二、准备目录与主密钥

```bash
sudo useradd --system --home /opt/mcp-gateway --shell /usr/sbin/nologin mcpgw
sudo mkdir -p /opt/mcp-gateway/data
sudo chown -R mcpgw:mcpgw /opt/mcp-gateway
```

把 jar 传到 `/opt/mcp-gateway/`，然后生成主密钥（**管理员口令另有一条，见本节末尾**）：

```bash
openssl rand -base64 32 | sudo tee /opt/mcp-gateway/master.key > /dev/null
sudo chown mcpgw:mcpgw /opt/mcp-gateway/master.key
sudo chmod 600 /opt/mcp-gateway/master.key
```

> **主密钥只生成一次，然后长期保存。**
> 子 MCP 的 headers 是用它做 AES-GCM 加密后落库的（`AesGcmCipher`）。把
> `export MCP_GATEWAY_MASTER_KEY=$(openssl rand -base64 32)` 写进启动脚本，意味着每次重启
> 都换一把新钥匙，此前存的所有子 MCP 配置会全部解不开。备份数据库时必须连同主密钥一起备份，
> 缺了任何一半都恢复不出来。

管理员口令同样只能来自环境变量，缺失或短于 8 位时**网关拒绝启动**：

```bash
openssl rand -base64 18   # 生成一个够长的随机口令
```

与主密钥不同的是，它**不需要长期保存**，也不参与任何加解密 —— 忘了就换一个新的重启即可，
已有数据不受影响。代价也在这里：**改口令必须重启**，界面上没有改密入口。

## 三、环境变量

完整清单见 [README.md](README.md#环境变量)，这里只讲 jar 部署必须动的几个。

### `BIND_ADDRESS` 与 `BASE_URL` 的区别

这两个最容易混淆，但管的是完全不同的两件事：一个是"我在哪听"，一个是"我告诉别人去哪找我"。

| | `MCP_GATEWAY_BIND_ADDRESS` | `MCP_GATEWAY_BASE_URL` |
| --- | --- | --- |
| 作用对象 | 本机的网络套接字 | 生成给 Agent 的接入 JSON 里的字符串 |
| 影响 | 决定进程绑定在哪块网卡上 | 决定 Agent 拿到的 URL 长什么样 |
| 形式 | 纯 IP：`0.0.0.0` / `127.0.0.1` | 完整 URL：`http://192.168.1.10:8080` |
| 配错的症状 | 连不上，TCP 层就被拒 | 网关自己跑得好好的，但 Agent 按那个地址连不上 |

`BIND_ADDRESS` 就是 Spring Boot 的 `server.address`，直接传给 JVM 去 `bind()`。改了它，
`ss -tlnp | grep 8080` 的输出会跟着变。

`BASE_URL` 不影响任何网络行为，它只是被拼进接入配置的一个字符串（`GatewayService:197`）。
关键在于它**刻意不从 `Host` / `X-Forwarded-*` 请求头推断**（需求 FR-05.1）：那些头由客户端控制，
能被伪造成攻击者的域名，接入 JSON 就会成为把 Agent 引向恶意端点的载体。代价是**配错了不会有
任何报错** —— 网关照常运行，只有 Agent 那边连不上。

两个都要配，且值不能互相顶替：

```bash
MCP_GATEWAY_BIND_ADDRESS=0.0.0.0                # 听所有网卡
MCP_GATEWAY_BASE_URL=http://192.168.1.10:8080   # 告诉 Agent 走这个具体 IP
```

- `BASE_URL` 里**绝不能写 `0.0.0.0`** —— 那是"任意地址"的占位符，不是可连接的地址。
- `BASE_URL` 一般只写到端口，不带路径；网关会自己接上 `/mcp/{slug}`。
  例外是挂在子路径下部署，那时它要写到前缀为止（`https://host/kbmcp`），见下。
- 只设 `BASE_URL` 而不动 `BIND_ADDRESS`，进程仍然只听 `127.0.0.1`，Agent 连过去是
  connection refused —— 这是最常见的一个坑。

前面挂了 Nginx 反代时，两者就完全无关了：

```bash
MCP_GATEWAY_BIND_ADDRESS=127.0.0.1              # 只对本机的 Nginx 开放
MCP_GATEWAY_BASE_URL=https://mcp.example.com    # Agent 走域名和 443
```

### `DB_PATH` 用绝对路径

默认值 `./data/mcp-gateway` 是相对**当前工作目录**的，换个目录启动就等于换了个空库。
systemd 虽然有 `WorkingDirectory`，仍然建议显式写绝对路径。

### 挂在子路径下

默认部署里本应用占着整个根路径：`/ui`（管理界面）、`/api`（管理接口）、`/app`（前端资源）、
`/mcp`（Agent 端点）。同一个域名上已经有别的应用占了 `/api` 之类的地址时，就得把这四类
地址整体搬到一个前缀下面，例如 `https://host/kbmcp`。

要改**三个地方**，少一个都是坏的，而且坏法各不相同：

| 改哪里 | 怎么写 | 漏了的症状 |
| --- | --- | --- |
| 构建 | `mvn -Dvite.base.path=/kbmcp -DskipTests -Dfrontend.test.skip=true clean package` | 页面能打开，但资源全 404（白屏），接口打到同域的别的应用上 |
| 运行 | `MCP_GATEWAY_CONTEXT_PATH=/kbmcp` | 整个前缀 404 |
| 反代 | 转发时**不要**剥掉前缀 | 剥两次等于没设，同样 404 |

第一行是实际踩过的那个坑：照着[「一、构建」](#一构建)抄了不带参数的命令，一路到浏览器
才发现白屏。验产物的命令见那一节，五秒钟的事。

外加一个不报错的：`MCP_GATEWAY_BASE_URL` 要写到前缀为止（`https://host/kbmcp`），
否则给 Agent 的接入 URL 会少一截 —— 管理界面一切正常，只有 Agent 连不上。

Docker Compose 部署只需在 `.env` 里设一个 `MCP_GATEWAY_CONTEXT_PATH=/kbmcp`：compose
把同一个变量既喂给构建参数 `VITE_BASE_PATH` 又喂给运行环境，两端不可能对不上。
但**改完要 `docker compose build`**，光 `up -d` 不会重打前端。

**前缀是打进 jar 的。** 前端资源地址在 index.html 里是绝对路径，只能构建期确定，所以同一份
产物不能既挂根路径又挂 `/kbmcp`；换前缀要重新构建，而不是改个环境变量重启。运行期的
`MCP_GATEWAY_CONTEXT_PATH` 必须与构建时的 `-Dvite.base.path` 是同一个值。

Nginx 的写法（注意 `proxy_pass` 结尾**不带路径**，这正是"不剥前缀"的写法；一旦写成
`proxy_pass http://127.0.0.1:8080/;` 就会剥掉前缀）：

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

验证（前缀内通、前缀外不通，两半都要看）：

```bash
curl -sI http://127.0.0.1:8080/kbmcp/ui/gateways | head -1   # 200
curl -sI http://127.0.0.1:8080/ui/gateways       | head -1   # 404
# 入口文档里的资源地址应当带着前缀
curl -s http://127.0.0.1:8080/kbmcp/ui/gateways | grep -o 'src="[^"]*"'
```

这条路径由 `SubPathDeploymentTest`（服务端）和 `frontend/test/base-path.spec.ts`（前端）守着。

## 四、以 systemd 运行

`export` + 前台 `java` 一断开 SSH 就没了，用 systemd 托管。

`/opt/mcp-gateway/env` —— 注意里面直接写 `KEY=VALUE`，**不要写 `export`**：

```
MCP_GATEWAY_MASTER_KEY=<master.key 里那串 base64>
MCP_GATEWAY_ADMIN_USERNAME=admin
MCP_GATEWAY_ADMIN_PASSWORD=<至少 8 位的口令>
MCP_GATEWAY_COOKIE_SECURE=false
MCP_GATEWAY_BIND_ADDRESS=0.0.0.0
MCP_GATEWAY_PORT=8080
MCP_GATEWAY_DB_PATH=/opt/mcp-gateway/data/mcp-gateway
MCP_GATEWAY_BASE_URL=http://<Agent 能访问到的地址>:8080
```

```bash
sudo chown mcpgw:mcpgw /opt/mcp-gateway/env
sudo chmod 600 /opt/mcp-gateway/env
```

`/etc/systemd/system/mcp-gateway.service`：

```ini
[Unit]
Description=MCP Gateway
After=network.target

[Service]
User=mcpgw
Group=mcpgw
WorkingDirectory=/opt/mcp-gateway
EnvironmentFile=/opt/mcp-gateway/env
ExecStart=/usr/bin/java -XX:MaxRAMPercentage=75.0 -jar /opt/mcp-gateway/mcp-gateway-1.2.1.jar
Restart=on-failure
RestartSec=5

# 主密钥和数据库都在环境里/磁盘上，收紧一点
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/opt/mcp-gateway/data

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now mcp-gateway
```

首次启动会由 Flyway 自动执行 `V1__init.sql` 建表（`baseline-on-migrate: false`，空库直接迁移）。

## 五、验证

```bash
# 1. 健康检查（本机）
curl -s http://127.0.0.1:8080/actuator/health          # 期望 {"status":"UP"}

# 2. 确认真的听在 0.0.0.0 而不是回环
ss -tlnp | grep 8080

# 3. 从 Agent 那台机器上验一遍，这才是真正要通的路径
curl -s http://<BASE_URL 里那个地址>:8080/actuator/health
```

管理界面：浏览器打开 `http://<地址>:8080/ui`。

`/actuator` 下除 `health` 外的端点全部关闭（需求 12.8），`health` 也不返回明细。
`health` 是唯一公开的非 UI 路径（容器 healthcheck 依赖它），其余路径落在 `denyAll` 上返回 401。

主密钥缺失、不是合法 Base64、或解码后不是 32 字节时，应用会**直接启动失败**并打印明确原因 ——
这是刻意的（需求 12.1），不会退化成弱加密或明文落库。查日志：

```bash
journalctl -u mcp-gateway -n 50
```

## 六、日常运维

### 备份

```bash
sudo systemctl stop mcp-gateway
sudo tar czf mcp-gateway-$(date +%F).tar.gz \
    -C /opt/mcp-gateway data master.key
sudo systemctl start mcp-gateway
```

数据库里含知识库返回的内容（需求 FR-06.4），备份文件需要按同等要求保护。**主密钥必须一起备份**，
否则恢复出来的库解不开子 MCP 的 headers。

### 升级

```bash
sudo systemctl stop mcp-gateway
sudo cp mcp-gateway-<新版本>.jar /opt/mcp-gateway/
# 同步更新 service 里的 ExecStart 文件名
sudo systemctl daemon-reload && sudo systemctl start mcp-gateway
```

Flyway 会在启动时自动补上新增的迁移。升级前先做一次备份。

### 单实例约束

H2 是文件库且以 `AUTO_SERVER=FALSE` 打开（单机单进程，需求 14）。**不要起第二个实例指向同一个
`MCP_GATEWAY_DB_PATH`**，会因文件锁冲突起不来。这个部署形态不支持水平扩容。

## 常见问题

| 症状 | 原因 |
| --- | --- |
| 本机 `curl` 通，外部连不上 | `MCP_GATEWAY_BIND_ADDRESS` 没设成 `0.0.0.0`；或防火墙/安全组没放行 |
| 网关正常，但 Agent 连不上 | `MCP_GATEWAY_BASE_URL` 填的地址 Agent 访问不到（写成了 `127.0.0.1` 或 `0.0.0.0`） |
| 启动失败，提示 master key | 主密钥缺失，或解码后不是 32 字节 |
| 启动失败，提示 ADMIN_PASSWORD | 管理员口令缺失，或短于 8 位 |
| 登录页填对了口令却一直退回登录页 | 反代做了 TLS 但没设 `MCP_GATEWAY_COOKIE_SECURE=true`；或明文 HTTP 却设成了 `true`（Cookie 不下发）|
| 登录返回 `TOO_MANY_ATTEMPTS` | 该来源连续失败 5 次被锁 5 分钟。反代后面所有请求来源 IP 相同，会退化成全局限速 |
| 管理界面操作返回 `FORBIDDEN` | CSRF 令牌过期。刷新页面重取即可 |
| 重启后子 MCP 配置全部报错 | 主密钥变了。恢复原来那把，别重新生成 |
| 重启后数据像是空的 | `MCP_GATEWAY_DB_PATH` 是相对路径，换工作目录启动指到了别的库 |
| 起不来，报文件锁 | 已经有一个实例占着同一个 H2 库 |
| 浏览器访问 MCP 端点 403 | Origin 校验（需求 12.6）。非浏览器客户端不带 Origin 头，不受影响 |
| 子路径部署下页面白屏、资源 404 | 构建时没给 `-Dvite.base.path`，或它与 `MCP_GATEWAY_CONTEXT_PATH` 不是同一个值 |
| 子路径部署下整个前缀 404 | 反代把前缀剥掉了（`proxy_pass` 结尾带了路径），或没设 `MCP_GATEWAY_CONTEXT_PATH` |
