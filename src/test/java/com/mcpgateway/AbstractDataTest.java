package com.mcpgateway;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * 数据层测试基类：跑真实的 H2 + 真实的 Flyway 迁移，每个测试方法结束后回滚。
 *
 * 用真库而不是 mock，是因为这一层要验证的恰恰是外键级联和唯一约束 —— 那些行为只存在于数据库里。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
public abstract class AbstractDataTest {

    @DynamicPropertySource
    static void registerMasterKey(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
    }
}
