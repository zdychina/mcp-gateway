package com.mcpgateway.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.stereotype.Component;

/**
 * 网关自身的版本号，来自构建期生成的 {@code META-INF/build-info.properties}。
 *
 * 存在的理由：这个版本号会通过 MCP 协议对外上报（下游看到的 clientInfo、上游 agent
 * 看到的 serverInfo），以前在两处写死成字符串，每次发版都得手工跟着改，漏一处就会
 * 对外报出一个假版本。
 *
 * 用 {@link ObjectProvider} 而不是直接注入 {@link BuildProperties}：那个 bean 由
 * Spring 的 ProjectInfoAutoConfiguration 按 build-info.properties 是否存在来决定，
 * 而这个文件是 spring-boot-maven-plugin 的 build-info 目标产出的 —— 绕开 Maven
 * 直接在 IDE 里编译运行时它不存在。版本号读不到不该拖垮启动，退化成
 * {@value #UNKNOWN} 就够了。
 */
@Component
public class GatewayVersion {

    /** 构建信息缺失时对外上报的占位值。宁可诚实说不知道，也不要报一个过期的数字。 */
    public static final String UNKNOWN = "unknown";

    private final String value;

    public GatewayVersion(ObjectProvider<BuildProperties> buildProperties) {
        BuildProperties build = buildProperties.getIfAvailable();
        this.value = (build == null) ? UNKNOWN : build.getVersion();
    }

    public String value() {
        return this.value;
    }

}
