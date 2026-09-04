package com.mcpgateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** 管理 API 测试基类：走完整的 controller -> service -> repository -> H2 链路。 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
public abstract class AbstractApiTest {

    @DynamicPropertySource
    static void registerMasterKey(DynamicPropertyRegistry registry) {
        registry.add("mcp-gateway.security.master-key", () -> TestMasterKey.BASE64);
        TestAdminCredentials.register(registry);
    }

    @Autowired
    private WebApplicationContext webApplicationContext;

    /**
     * 已登录的调用方。
     *
     * 管理 API 全部需要登录（需求 12.8），业务用例关心的不是这一点，所以默认就带上身份和
     * CSRF 令牌 —— 否则每个用例都要在每个请求上重复 {@code .with(csrf())}，噪音远大于价值。
     *
     * 认证本身由 api 包下的 AuthApiTest 和 ApiAuthorizationInvariantsTest 专门覆盖。
     */
    protected MockMvc mockMvc;

    /** 未登录的调用方，用来验证"没登录就是进不去"。 */
    protected MockMvc anonymousMockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @BeforeEach
    void buildMockMvc() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
                .apply(springSecurity())
                // defaultRequest 的后置处理器会并入每一个请求，见 MockHttpServletRequestBuilder.merge。
                .defaultRequest(get("/")
                        .with(user(TestAdminCredentials.USERNAME)
                                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .with(csrf()))
                .build();

        this.anonymousMockMvc = MockMvcBuilders.webAppContextSetup(this.webApplicationContext)
                .apply(springSecurity())
                .build();
    }

    protected static String uniqueSlug(String prefix) {
        return prefix + "-" + Long.toString(System.nanoTime(), 36);
    }
}
