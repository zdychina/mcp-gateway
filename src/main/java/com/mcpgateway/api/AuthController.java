package com.mcpgateway.api;

import com.mcpgateway.api.dto.LoginRequest;
import com.mcpgateway.api.dto.SessionResponse;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.security.LoginThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理端登录（需求 12.8）。
 *
 * 为什么是手写的控制器而不是 Spring Security 的 formLogin：后者只吃
 * application/x-www-form-urlencoded，而"管理接口只收 application/json"是 SECURITY.md 里
 * 明写的一道跨站保护。用 formLogin 就等于自己拆了它。
 *
 * 代价是 formLogin 顺手做掉的两件事必须在这里显式做，漏掉任何一件都是漏洞：
 * <ol>
 *   <li>会话固定防护 —— 登录后换掉会话 ID；</li>
 *   <li>把 SecurityContext 存进会话 —— Spring Security 6 不再自动保存。</li>
 * </ol>
 * 两件都交给 {@code SessionAuthenticationStrategy} 和 {@code SecurityContextRepository}
 * 这两个 Security 自己的组件，而不是手工拼装。
 *
 * 这里没有注册、没有改密、没有找回密码：凭证来自环境变量（见 {@code AdminAccount}），
 * 改口令要重启。需求 3.2 明确不含权限系统，这一条不打算突破。
 */
@RestController
public class AuthController {

    /** 公开：未登录才需要它。 */
    public static final String LOGIN_PATH = "/api/auth/login";

    /** 需要登录：会话都没了就没什么可退的，让它落到统一的 401 上。 */
    public static final String LOGOUT_PATH = "/api/auth/logout";

    /** 公开：前端启动时靠它决定渲染登录页还是主界面。 */
    public static final String SESSION_PATH = "/api/auth/session";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;

    private final SecurityContextRepository securityContextRepository;

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    private final CsrfTokenRepository csrfTokenRepository;

    private final LoginThrottle loginThrottle;

    public AuthController(AuthenticationManager authenticationManager,
            SecurityContextRepository securityContextRepository,
            SessionAuthenticationStrategy sessionAuthenticationStrategy,
            CsrfTokenRepository csrfTokenRepository,
            LoginThrottle loginThrottle) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.sessionAuthenticationStrategy = sessionAuthenticationStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
        this.loginThrottle = loginThrottle;
    }

    @PostMapping(LOGIN_PATH)
    public ApiResponse<SessionResponse> login(@Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest, HttpServletResponse httpResponse) {

        String source = httpRequest.getRemoteAddr();

        /*
         * 先查锁再验口令，顺序不能反：锁定期间仍然比对一次，攻击者就能通过响应时间
         * 区分"锁着且口令对"和"锁着且口令不对"，限速也就白做了。
         */
        if (this.loginThrottle.isLocked(source)) {
            log.warn("throttled admin login attempt from [{}]", source);
            throw new GatewayException(ErrorCode.TOO_MANY_ATTEMPTS,
                    "too many failed login attempts; try again later");
        }

        Authentication authentication;
        try {
            authentication = this.authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.username(), request.password()));
        }
        catch (AuthenticationException ex) {
            this.loginThrottle.recordFailure(source);
            /*
             * 不记用户名、不记口令、不区分"用户名不对"和"口令不对"。
             * 前者进日志等于把爆破字典替人整理好，后者是免费送给探测者的信息。
             */
            log.warn("rejected admin login from [{}]", source);
            throw new GatewayException(ErrorCode.UNAUTHORIZED, "invalid username or password");
        }

        this.loginThrottle.recordSuccess(source);

        /*
         * 会话固定防护 + CSRF 令牌轮换，两件都由 SecurityConfig 里组合好的策略完成。
         * 权限发生变化之后旧的会话 ID 和旧的 CSRF 令牌都必须作废。
         */
        this.sessionAuthenticationStrategy.onAuthentication(authentication, httpRequest, httpResponse);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);
        // Spring Security 6 起必须显式保存，否则登录成功但下一个请求依然是匿名的。
        this.securityContextRepository.saveContext(context, httpRequest, httpResponse);

        log.info("admin [{}] signed in from [{}]", authentication.getName(), source);
        return ApiResponse.ok(SessionResponse.of(authentication.getName()));
    }

    @PostMapping(LOGOUT_PATH)
    public ApiResponse<SessionResponse> logout(HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {

        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            /*
             * 服务端销毁会话，而不是只清 Cookie —— 后者留下的会话 ID 在服务端仍然有效，
             * 任何拿到过它的人都还能继续用。浏览器里那枚失效的 Cookie 无害。
             */
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
        // 让 CSRF Cookie 一起过期，下一次访问会拿到全新的一枚。
        this.csrfTokenRepository.saveToken(null, httpRequest, httpResponse);

        return ApiResponse.ok(SessionResponse.anonymous());
    }

    /**
     * 当前登录态。公开接口，因此未登录时除了"没登录"以外什么都不能透露 ——
     * 连配置好的管理员用户名都不行。
     */
    @GetMapping(SESSION_PATH)
    public ApiResponse<SessionResponse> session() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!isSignedIn(authentication)) {
            return ApiResponse.ok(SessionResponse.anonymous());
        }
        return ApiResponse.ok(SessionResponse.of(authentication.getName()));
    }

    /**
     * 匿名请求拿到的不是 null 而是 AnonymousAuthenticationToken，且它的
     * {@code isAuthenticated()} 返回 true。只判空或只判 isAuthenticated 都会把匿名当成已登录。
     */
    private static boolean isSignedIn(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }
}
