package com.mcpgateway;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 对源码本身的检查，守住几条只靠代码评审会慢慢失效的安全约束（需求 §12）。
 *
 * 这些规则的共同点：违反了不会让任何功能测试变红，但会实实在在地泄漏凭证或削弱边界。
 * 与其依赖每次评审都记得看，不如让构建来盯。
 */
class SecurityInvariantsTest {

    private static final Path MAIN_JAVA = Path.of("src/main/java");

    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    private record SourceFile(Path path, String content) {
    }

    private static List<SourceFile> sourcesUnder(Path root, String suffix) throws IOException {
        try (Stream<Path> paths = Files.walk(root)) {
            List<SourceFile> files = new ArrayList<>();
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(suffix)).toList()) {
                files.add(new SourceFile(path, Files.readString(path, StandardCharsets.UTF_8)));
            }
            return files;
        }
    }

    @Test
    @DisplayName("需求 12.1：源码和配置里没有硬编码的密钥、令牌或口令")
    void noHardcodedSecrets() throws IOException {
        // 只匹配"赋了一个足够长的字面量"的形态，短的枚举名和占位符不会误报。
        Pattern suspicious = Pattern.compile(
                "(?i)(secret|passwd|password|api[-_]?key|private[-_]?key)\\s*=\\s*\"[^\"]{8,}\"");

        List<String> hits = new ArrayList<>();
        for (SourceFile file : sourcesUnder(MAIN_JAVA, ".java")) {
            if (suspicious.matcher(file.content()).find()) {
                hits.add(file.path().toString());
            }
        }
        assertThat(hits).as("硬编码密钥").isEmpty();

        // 配置里的敏感项必须是环境变量占位符，不能有内置默认值。
        String applicationYml = Files.readString(MAIN_RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);
        assertThat(applicationYml).contains("master-key: ${MCP_GATEWAY_MASTER_KEY:}");
        assertThat(applicationYml).doesNotContain("mcpgw_");
    }

    @Test
    @DisplayName("需求 12.1 / 12.8：管理员口令没有内置默认值，只能来自环境变量")
    void adminPasswordHasNoDefault() throws IOException {
        String applicationYml = Files.readString(MAIN_RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        /*
         * 与主密钥同样的道理：一旦这里有了默认值，所有没读完文档的部署都会用同一把钥匙，
         * 而管理端此时已经允许绑到内网地址了。
         */
        assertThat(applicationYml).contains("password: ${MCP_GATEWAY_ADMIN_PASSWORD:}");
    }

    @Test
    @DisplayName("需求 12.8：会话 Cookie 保持 HttpOnly + SameSite=Strict，secure 由部署决定")
    void sessionCookieStaysHardened() throws IOException {
        String applicationYml = Files.readString(MAIN_RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        // HttpOnly：XSS 偷不走登录态。SameSite=Strict：当前挡住 CSRF 的主力。
        assertThat(applicationYml).contains("http-only: true");
        assertThat(applicationYml).contains("same-site: strict");
        // secure 必须可配：默认部署是 localhost 明文 HTTP，写死 true 会让人登不进去。
        assertThat(applicationYml).contains("secure: ${MCP_GATEWAY_COOKIE_SECURE:false}");
    }

    @Test
    @DisplayName("需求 12.8：登录接口不用 formLogin / httpBasic —— 那会打掉“只收 JSON”这层保护")
    void loginDoesNotFallBackToFormOrBasic() throws IOException {
        /*
         * formLogin 吃的是 application/x-www-form-urlencoded，httpBasic 会让浏览器弹出
         * 原生认证框。两者都与"管理接口只收 application/json + 登录页由 SPA 渲染"冲突，
         * 而它们又都只是一行配置，很容易在调试某个问题时被顺手加上。
         */
        // 只认真正的调用。注释和 Javadoc 里要解释"为什么不用它们"，按名字裸扫会全部误报。
        Pattern enabled = Pattern.compile("\\.(formLogin|httpBasic)\\s*\\(");

        List<String> hits = new ArrayList<>();
        for (SourceFile file : sourcesUnder(MAIN_JAVA, ".java")) {
            if (enabled.matcher(file.content()).find()) {
                hits.add(file.path().toString());
            }
        }
        assertThat(hits).as("启用了表单登录或 Basic 认证的位置").isEmpty();
    }

    @Test
    @DisplayName("需求 12.8：只有 Agent 那条链可以关掉 CSRF，管理端不行")
    void csrfIsDisabledOnlyForTheAgentChain() throws IOException {
        /*
         * /mcp/** 上的 Agent 带的是 Bearer 令牌而不是 Cookie，天然不受 CSRF 影响，关掉是对的。
         * 管理端带的是会话 Cookie，关掉就等于把 CSRF 面完全敞开 —— 而两处写法一模一样，
         * 复制粘贴过去不会有任何编译或测试提示。
         */
        String securityConfig = Files.readString(
                MAIN_JAVA.resolve("com/mcpgateway/security/SecurityConfig.java"), StandardCharsets.UTF_8);

        long disabled = securityConfig.lines()
                .filter(line -> line.contains(".csrf(") && line.contains("disable"))
                .count();

        assertThat(disabled).as("关闭 CSRF 的位置数量").isEqualTo(1);
    }

    @Test
    @DisplayName("需求 12.8：不给管理 API 放开 CORS —— 那会打掉浏览器预检这层保护")
    void noCorsIsEnabled() throws IOException {
        /*
         * 管理端已经有了登录、SameSite=Strict 的会话 Cookie 和 CSRF 令牌，但"不发任何
         * CORS 响应头"仍是独立的一层：跨站 fetch 过不了预检，连试的机会都没有。
         * 放开 CORS 是一个独立决策，不因为多了登录就顺手做掉 ——
         * 一旦有人加了 @CrossOrigin 或 addCorsMappings，这一层就没了。
         */
        List<String> hits = new ArrayList<>();
        for (SourceFile file : sourcesUnder(MAIN_JAVA, ".java")) {
            if (file.content().contains("CrossOrigin")
                    || file.content().contains("addCorsMappings")
                    || file.content().contains("Access-Control-Allow")) {
                hits.add(file.path().toString());
            }
        }
        assertThat(hits).as("放开 CORS 的位置").isEmpty();
    }

    @Test
    @DisplayName("需求 12.2：解密子 MCP 凭证的入口只有编解码器和下游客户端工厂两处")
    void plaintextCredentialsHaveExactlyTwoCallSites() throws IOException {
        List<String> callers = new ArrayList<>();
        for (SourceFile file : sourcesUnder(MAIN_JAVA, ".java")) {
            if (file.content().contains(".decrypt(")) {
                callers.add(file.path().getFileName().toString());
            }
        }
        /*
         * 明文凭证的活动范围越小越好。DownstreamHeaderCodec 负责密文和映射的互转，
         * DownstreamClientFactory 是唯一真正使用明文的地方（塞进请求构造器后即丢弃）。
         * 出现第三处就值得停下来想想：那里为什么需要明文。
         */
        assertThat(callers).containsExactlyInAnyOrder(
                "DownstreamHeaderCodec.java", "DownstreamClientFactory.java");
    }

    @Test
    @DisplayName("需求 12.5：日志不直接打印 header 映射，只走遮罩后的描述")
    void headersAreNeverLoggedRaw() throws IOException {
        Pattern logCall = Pattern.compile("log\\.[a-z]+\\([^;]*", Pattern.DOTALL);
        List<String> hits = new ArrayList<>();

        for (SourceFile file : sourcesUnder(MAIN_JAVA, ".java")) {
            var matcher = logCall.matcher(file.content());
            while (matcher.find()) {
                String call = matcher.group();
                // 提到 headers 却没有经过遮罩的日志调用就是问题。
                if (call.contains("headers") && !call.contains("describeForLog")
                        && !call.contains("encryptedHeaders")) {
                    hits.add(file.path().getFileName() + ": " + call.lines().findFirst().orElse(""));
                }
            }
        }
        assertThat(hits).as("未遮罩就写日志的 header").isEmpty();
    }

    @Test
    @DisplayName("需求 12.6：默认只监听 localhost，容器部署必须显式覆盖")
    void defaultBindAddressIsLoopback() throws IOException {
        String applicationYml = Files.readString(MAIN_RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        assertThat(applicationYml).contains("${MCP_GATEWAY_BIND_ADDRESS:127.0.0.1}");
    }

    @Test
    @DisplayName("需求 12.8：actuator 默认只放开 health")
    void actuatorExposureIsMinimal() throws IOException {
        String applicationYml = Files.readString(MAIN_RESOURCES.resolve("application.yml"),
                StandardCharsets.UTF_8);

        assertThat(applicationYml).contains("include: health");
        assertThat(applicationYml).doesNotContain("include: \"*\"").doesNotContain("include: '*'");
    }

    @Test
    @DisplayName("需求 13.3：MCP SDK 版本固定，依赖不使用动态版本")
    void dependencyVersionsArePinned() throws IOException {
        String pom = Files.readString(Path.of("pom.xml"), StandardCharsets.UTF_8);

        assertThat(pom).containsPattern("<mcp\\.sdk\\.version>\\d+\\.\\d+\\.\\d+</mcp\\.sdk\\.version>");

        /*
         * 只检查 <version> 元素本身，不能整份文件扫 "SNAPSHOT" ——
         * 项目自己的版本在开发期是 -SNAPSHOT，那是预发布的正常形态，
         * 要防的是**依赖**用了动态版本。SNAPSHOT 依赖另有 enforcer 的
         * requireReleaseDeps 在构建期拦截。
         */
        // 只看 <dependency> 块里的版本。enforcer 的 requireJavaVersion/requireMavenVersion
        // 也用 <version> 元素并且刻意写成范围（如 [21,)），那是对构建环境的要求，不是依赖。
        Pattern dependencyBlock = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL);
        Pattern versionElement = Pattern.compile("<version>([^<]+)</version>");

        List<String> dynamic = new ArrayList<>();
        var blocks = dependencyBlock.matcher(pom);
        while (blocks.find()) {
            var versions = versionElement.matcher(blocks.group(1));
            while (versions.find()) {
                String version = versions.group(1).trim();
                boolean isDynamic = version.equals("LATEST") || version.equals("RELEASE")
                        || version.startsWith("[") || version.startsWith("(")
                        || version.endsWith("-SNAPSHOT");
                if (isDynamic) {
                    dynamic.add(version);
                }
            }
        }
        assertThat(dynamic).as("依赖里的动态或快照版本").isEmpty();
    }
}
