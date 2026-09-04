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
 *
 * webEnvironment 是 MOCK 而不是 NONE，尽管这一层完全用不到 web：SecurityConfig 的
 * @EnableWebSecurity 和两条过滤器链只有在 servlet 上下文里才装配得起来，NONE 会让上下文
 * 直接起不来。与其为了一个不存在的部署形态给生产代码加 @ConditionalOnWebApplication，
 * 不如让测试用应用真实的形态。顺带的好处是这里和 AbstractApiTest 的上下文配置完全一致，
 * Spring 会复用同一个上下文而不是各起一个。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class AbstractDataTest {

    @DynamicPropertySource
    static void registerMasterKey(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
        TestAdminCredentials.register(registry);
    }
}
