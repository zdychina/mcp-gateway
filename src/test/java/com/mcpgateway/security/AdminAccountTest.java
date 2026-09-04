package com.mcpgateway.security;

import com.mcpgateway.config.GatewayProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 管理员账号的启动期校验（需求 12.1 / 12.8）。
 *
 * 这里守的是"宁可起不来也不要退化成弱口令"，和 {@link AesGcmCipherTest} 对主密钥的要求一致。
 */
class AdminAccountTest {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private static final String STRONG_ENOUGH = "a-long-enough-passphrase";

    private static GatewayProperties propertiesWith(String username, String password) {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().getAdmin().setUsername(username);
        properties.getSecurity().getAdmin().setPassword(password);
        return properties;
    }

    @Test
    @DisplayName("需求 12.1：没配口令时应用起不来，而不是退化成某个默认口令")
    void refusesToStartWithoutAPassword() {
        assertThatThrownBy(() -> new AdminAccount(propertiesWith("admin", null), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_GATEWAY_ADMIN_PASSWORD");

        assertThatThrownBy(() -> new AdminAccount(propertiesWith("admin", "   "), ENCODER))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("过短的口令直接拒绝启动 —— 有了登录才敢绑内网地址，口令弱等于白做")
    void refusesShortPasswords() {
        assertThatThrownBy(() -> new AdminAccount(propertiesWith("admin", "short"), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least");
    }

    @Test
    @DisplayName("启动失败的文案里不得出现收到的口令 —— 那种日志经常被整段贴进工单")
    void startupFailureNeverEchoesThePassword() {
        String tooShort = "abc123";

        assertThatThrownBy(() -> new AdminAccount(propertiesWith("admin", tooShort), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(tooShort);
    }

    @Test
    @DisplayName("用户名为空同样拒绝启动")
    void refusesBlankUsername() {
        assertThatThrownBy(() -> new AdminAccount(propertiesWith("  ", STRONG_ENOUGH), ENCODER))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MCP_GATEWAY_ADMIN_USERNAME");
    }

    @Test
    @DisplayName("需求 12.3 的同类要求：账号里只留 BCrypt 摘要，不留明文")
    void keepsOnlyABcryptDigest() {
        AdminAccount account = new AdminAccount(propertiesWith("operator", STRONG_ENOUGH), ENCODER);

        String stored = account.toUserDetails().getPassword();

        assertThat(stored).isNotEqualTo(STRONG_ENOUGH);
        assertThat(stored).startsWith("$2");
        assertThat(ENCODER.matches(STRONG_ENOUGH, stored)).isTrue();
        assertThat(account.username()).isEqualTo("operator");
    }

    @Test
    @DisplayName("用户名两侧的空白被去掉 —— 环境变量里带一个尾随空格是常见的部署事故")
    void trimsTheUsername() {
        AdminAccount account = new AdminAccount(propertiesWith(" operator ", STRONG_ENOUGH), ENCODER);

        assertThat(account.username()).isEqualTo("operator");
    }
}
