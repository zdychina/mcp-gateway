package com.mcpgateway;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 测试用主密钥：每次 JVM 启动随机生成一把，绝不把密钥字面量写进仓库（需求 12.1）。
 * static final 保证同一次构建内所有测试上下文用的是同一把钥匙。
 */
public final class TestMasterKey {

    public static final String BASE64 = generate();

    private TestMasterKey() {
    }

    public static String generate() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
