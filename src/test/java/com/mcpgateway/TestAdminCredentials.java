package com.mcpgateway;

import org.springframework.test.context.DynamicPropertyRegistry;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 测试用管理员凭证：每次 JVM 启动随机生成一把口令，绝不把凭证字面量写进仓库（需求 12.1）。
 *
 * 与 {@link TestMasterKey} 同样的做法和同样的理由 —— static final 保证同一次构建内
 * 所有测试上下文用的是同一套凭证，Spring 的上下文缓存因此不会被打散。
 */
public final class TestAdminCredentials {

    public static final String USERNAME = "test-admin";

    /** 长度必须满足 AdminAccount.MIN_PASSWORD_LENGTH，否则上下文起不来。 */
    public static final String PASSWORD = generate();

    private TestAdminCredentials() {
    }

    /** 每个 @SpringBootTest 都要调一次，否则应用会因为"口令未配置"启动失败。 */
    public static void register(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.admin.username", () -> USERNAME);
        registry.add("mcp-gateway.security.admin.password", () -> PASSWORD);
    }

    private static String generate() {
        byte[] entropy = new byte[24];
        new SecureRandom().nextBytes(entropy);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
    }
}
