package com.mcpgateway.security;

import com.mcpgateway.config.GatewayProperties;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * 管理端的唯一账号（需求 12.8）。
 *
 * 凭证只来自环境变量，与主密钥同样的处理方式：没有默认值，缺失或过弱时直接让应用启动失败，
 * 而不是退化成一个人人都知道的弱口令。参见 {@link AesGcmCipher#loadKey}。
 *
 * 口令在这里被 BCrypt 哈希一次就丢掉明文引用。BCrypt 而不是 SHA-256 是刻意的 ——
 * {@link AccessTokenService} 那边用快哈希是因为令牌是 256 位随机串；人类口令熵低，
 * 必须用慢哈希，这也是那个类的注释里特意留了一句"这一条不适用于人类口令"的原因。
 */
@Component
public class AdminAccount {

    /**
     * 口令长度下限。
     *
     * 12 位不是随口定的：管理端有了登录之后就允许绑到内网地址，等于把登录接口暴露给
     * 一个可以持续尝试的网络。BCrypt 加登录限速能把在线爆破压到很慢，但前提是口令
     * 本身不在字典的前几万条里。
     */
    private static final int MIN_PASSWORD_LENGTH = 12;

    private final String username;

    /** BCrypt 摘要。明文口令在构造函数结束后不再被本类持有。 */
    private final String passwordHash;

    public AdminAccount(GatewayProperties properties, PasswordEncoder passwordEncoder) {
        GatewayProperties.Admin admin = properties.getSecurity().getAdmin();
        this.username = requireUsername(admin.getUsername());
        this.passwordHash = passwordEncoder.encode(requirePassword(admin.getPassword()));
    }

    private static String requireUsername(String username) {
        if (username == null || username.isBlank()) {
            throw new IllegalStateException(
                    "MCP_GATEWAY_ADMIN_USERNAME must not be blank");
        }
        return username.trim();
    }

    /**
     * 需求 12.1：口令不得硬编码，只能来自环境变量。
     *
     * 异常文案里绝不能出现收到的值，哪怕是长度以外的片段 —— 启动失败的日志经常被整段贴进
     * 工单和聊天窗口。
     */
    private static String requirePassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "MCP_GATEWAY_ADMIN_PASSWORD is not configured; set it to a passphrase of at least "
                            + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException(
                    "MCP_GATEWAY_ADMIN_PASSWORD must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        return password;
    }

    public String username() {
        return this.username;
    }

    /**
     * 供 {@code UserDetailsService} 使用的账号视图。
     *
     * 用 {@code User.withUsername(...)} 而不是自定义实现，是为了让 Spring Security 的
     * {@code DaoAuthenticationProvider} 走它自己的那套流程 —— 其中包括账号不存在时仍做一次
     * 假的口令比对，避免通过响应时间区分"用户名不对"和"口令不对"。
     */
    public UserDetails toUserDetails() {
        return User.withUsername(this.username)
                .password(this.passwordHash)
                .authorities("ROLE_ADMIN")
                .build();
    }
}
