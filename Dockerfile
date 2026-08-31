# syntax=docker/dockerfile:1

# ---------------------------------------------------------------- 构建阶段
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# 先只复制 pom 并预取依赖：源码改动不会让依赖层失效，重建快很多。
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline

COPY src ./src
# 镜像构建不跑测试：测试需要真实的随机端口和临时数据库，属于 CI 的职责。
# CI 应当先 `mvn verify`（含覆盖率门禁）通过，再来构建镜像。
RUN mvn -B -q -DskipTests package

# ---------------------------------------------------------------- 运行阶段
FROM eclipse-temurin:21-jre-noble AS runtime

# 不以 root 运行。数据目录归这个用户所有，否则挂载卷后写不进去。
RUN groupadd --system --gid 1001 mcpgw \
 && useradd --system --uid 1001 --gid mcpgw --home /app --shell /usr/sbin/nologin mcpgw \
 && mkdir -p /app/data \
 && chown -R mcpgw:mcpgw /app

WORKDIR /app
COPY --from=build --chown=mcpgw:mcpgw /build/target/mcp-gateway-*.jar /app/mcp-gateway.jar

USER mcpgw

# 容器里必须监听 0.0.0.0，否则端口映射进来也连不上。
# 注意这就等于放弃了"默认只监听 localhost"那道保护（需求 12.6 / 12.8）：
# 容器端口只能发布到本机或可信内网，绝不能直接暴露到公网。
ENV MCP_GATEWAY_BIND_ADDRESS=0.0.0.0 \
    MCP_GATEWAY_PORT=8080 \
    MCP_GATEWAY_DB_PATH=/app/data/mcp-gateway \
    JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

# H2 文件库放在卷里。库中含知识库返回的内容，按需求 FR-06.4 需要按部署要求保护。
VOLUME ["/app/data"]

EXPOSE 8080

# MCP_GATEWAY_MASTER_KEY 没有默认值，缺失时应用会启动失败 —— 这是刻意的（需求 12.1）。
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD ["sh", "-c", "wget -q -O - http://127.0.0.1:${MCP_GATEWAY_PORT}/actuator/health | grep -q UP"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/mcp-gateway.jar"]
