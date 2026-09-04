package com.mcpgateway.security;

import com.mcpgateway.api.ApiErrorWriter;
import com.mcpgateway.api.AuthController;
import com.mcpgateway.config.GatewayProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.CompositeSessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfAuthenticationStrategy;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import java.util.List;

/**
 * 管理端登录的全部策略（需求 12.8）。
 *
 * 三条链路的边界在这里划定，读这个文件应该能一次看清"谁需要登录、谁不需要"：
 *
 * <table>
 *   <tr><th>链路</th><th>认证方式</th></tr>
 *   <tr><td>{@code /mcp/**}</td><td>网关访问令牌（Bearer），由 GatewayMcpDispatcherServlet 自己校验</td></tr>
 *   <tr><td>{@code /api/**}</td><td>会话 Cookie，本文件的第二条链</td></tr>
 *   <tr><td>{@code /ui/**}、{@code /app/**}</td><td>公开 —— 登录页由 SPA 自己渲染</td></tr>
 * </table>
 *
 * 几条不能想当然的地方：
 *
 * <ul>
 *   <li><b>{@code /mcp/**} 必须单独一条链。</b> 它是 ServletRegistrationBean 注册的独立 servlet，
 *       但 Security 的过滤器映射在 {@code /*} 上，会连 Agent 流量一起拦。不显式开洞的话所有 Agent
 *       立刻 401；就算 permitAll 了，默认策略还会给每个 Agent 请求种一个 JSESSIONID，
 *       把无状态的 MCP 端点变成有状态的。</li>
 *   <li><b>没有启用 formLogin。</b> 它吃的是 application/x-www-form-urlencoded，而"管理接口只收
 *       application/json"是 SECURITY.md 里明写的一道跨站保护。登录接口因此是一个普通的
 *       {@link AuthController}，收 JSON、回统一信封。</li>
 *   <li><b>CORS 仍然一个头都不放。</b> 有了登录之后 CORS 才"有得谈"，但那是另一个独立决策，
 *       {@code SecurityInvariantsTest.noCorsIsEnabled} 继续把关。</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Agent 的 MCP 端点。
     *
     * 与 {@code McpServerConfiguration.MCP_PATH_PATTERN}（{@code /mcp/*}）指同一批地址，
     * 但那是 servlet 映射语法、这里是 Spring 的路径模式，两种通配写法不通用，只能各写一份。
     *
     * <b>必须显式构造 matcher，不能写成 {@code securityMatcher("/mcp/**")}。</b>
     * 那个字符串重载在有 Spring MVC 时会生成 MvcRequestMatcher，而它按**servlet 内相对路径**
     * 匹配：{@code /mcp/*} 是自己注册的一个 servlet，请求 {@code /mcp/foo} 在它眼里的路径是
     * {@code /foo}，与 {@code /mcp/**} 匹配不上 —— 于是 Agent 流量会静悄悄地落到管理端那条链上，
     * 撞在 CSRF 校验上返回 403。这条链的存在意义正好被这个默认值抵消掉，且不会有任何报错提示。
     */
    private static final RequestMatcher MCP_REQUESTS =
            PathPatternRequestMatcher.withDefaults().matcher("/mcp/**");

    /** 前端 SPA 的入口与产物。公开 —— 未登录时要靠它渲染登录页。 */
    private static final String[] PUBLIC_UI_PATTERNS = {
            "/", "/ui", "/ui/**", "/app/**", "/favicon.ico", "/error"
    };

    /** 容器 healthcheck 依赖它，且 application.yml 里已经把 show-details 关到 never。 */
    private static final String[] PUBLIC_HEALTH_PATTERNS = {
            "/actuator/health", "/actuator/health/**"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * 单账号，内存里。没有用户表，也不打算有（需求 3.2）。
     *
     * 声明了这个 Bean 之后 Boot 的 UserDetailsServiceAutoConfiguration 会退避，
     * 启动日志里那句 "Using generated security password" 也就不会出现 —— 那句话本身
     * 就是往日志里写凭证。
     */
    @Bean
    public UserDetailsService userDetailsService(AdminAccount adminAccount) {
        return new InMemoryUserDetailsManager(adminAccount.toUserDetails());
    }

    /**
     * 走 DaoAuthenticationProvider 而不是自己比对哈希，图的是它内置的一条：账号不存在时
     * 仍然做一次假的口令比对，因此"用户名不对"和"口令不对"在响应时间上无法区分。
     */
    @Bean
    public AuthenticationManager authenticationManager(
            UserDetailsService userDetailsService, PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        provider.setHideUserNotFoundExceptions(true);
        return new ProviderManager(provider);
    }

    /** 登录态存在服务端会话里，Cookie 只带一个猜不到的会话 ID。 */
    @Bean
    public SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    /**
     * CSRF 令牌。
     *
     * 加会话 Cookie 会引入这个项目此前不存在的 CSRF 面：浏览器会自动带上 Cookie，
     * 跨站发起的请求就有了身份。原来"没有登录所以没有 CSRF"的论证从此失效。
     *
     * Cookie 必须可被 JS 读取（withHttpOnlyFalse），前端才能把它回填到 X-XSRF-TOKEN 头上。
     * 这不削弱防护：CSRF 依赖的是"跨站脚本读不到本站 Cookie"，而不是"本站脚本读不到"。
     */
    @Bean
    public CsrfTokenRepository csrfTokenRepository(GatewayProperties properties) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        boolean secure = properties.getSecurity().isCookieSecure();
        repository.setCookieCustomizer(cookie -> cookie.sameSite("Strict").secure(secure));
        return repository;
    }

    /**
     * CSRF 令牌的读写方式。
     *
     * 两个默认值都必须改，少改一个就是一个只在特定时序下复现的 403：
     *
     * <ul>
     *   <li>Spring Security 6 默认的 {@code XorCsrfTokenRequestAttributeHandler}（BREACH 防护）
     *       会让 Cookie 里的值与服务端期望值对不上，SPA 直接回传必然 403。</li>
     *   <li>{@code csrfRequestAttributeName} 设为 null 才会**每次请求都真的解析令牌**。
     *       保持默认的话令牌是惰性的：没人读它就不会生成，Cookie 也就不下发 ——
     *       登录后轮换令牌那一步尤其致命，旧 Cookie 已删、新 Cookie 没发，
     *       客户端下一个写请求必然 403。</li>
     * </ul>
     */
    @Bean
    public CsrfTokenRequestHandler csrfTokenRequestHandler() {
        CsrfTokenRequestAttributeHandler handler = new CsrfTokenRequestAttributeHandler();
        handler.setCsrfRequestAttributeName(null);
        return handler;
    }

    /**
     * 登录成功那一刻要做的两件事，交给 Security 自己的策略而不是手工拼装：
     *
     * <ul>
     *   <li>换掉会话 ID —— 会话固定防护。没有会话时它什么都不做，不会平白建一个。</li>
     *   <li>轮换 CSRF 令牌 —— 权限变了，登录前拿到的那枚必须作废。</li>
     * </ul>
     *
     * formLogin 本来会顺手做掉这两件；不用它就必须自己接上，见 {@link AuthController#login}。
     */
    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy(
            CsrfTokenRepository csrfTokenRepository, CsrfTokenRequestHandler csrfTokenRequestHandler) {
        CsrfAuthenticationStrategy csrfStrategy = new CsrfAuthenticationStrategy(csrfTokenRepository);
        // 必须和过滤器链用同一个处理器，否则轮换出来的新令牌不会真的下发。
        csrfStrategy.setRequestHandler(csrfTokenRequestHandler);
        return new CompositeSessionAuthenticationStrategy(List.of(
                new ChangeSessionIdAuthenticationStrategy(), csrfStrategy));
    }

    /**
     * 第一条链：Agent 的 MCP 端点，Security 在这里什么都不做。
     *
     * 令牌校验在 GatewayMcpDispatcherServlet 里，Origin 校验在 OriginValidator 里，
     * 都早于本次改动就存在且经过验收测试。这条链唯一的职责是**保证登录功能没碰坏它们**：
     * 不种 Cookie、不建会话、不查 CSRF 令牌。
     */
    @Bean
    @Order(1)
    public SecurityFilterChain mcpSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(MCP_REQUESTS)
                // Agent 带的是 Bearer 令牌，不是 Cookie，天然不受 CSRF 影响。
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(RequestCacheConfigurer::disable)
                .anonymous(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // STATELESS 已经隐含了这一条，显式写出来是因为它是本条链存在的理由。
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * 第二条链：管理端。
     *
     * 注意 authorizeHttpRequests 是**首个匹配生效**，公开的三组必须排在
     * {@code /api/**} 之前；最后的 anyRequest().denyAll() 是刻意的 ——
     * 新加的路径默认拒绝，而不是默认放行。
     */
    @Bean
    @Order(2)
    public SecurityFilterChain adminSecurityFilterChain(
            HttpSecurity http,
            CsrfTokenRepository csrfTokenRepository,
            CsrfTokenRequestHandler csrfTokenRequestHandler,
            SecurityContextRepository securityContextRepository,
            ApiErrorWriter errorWriter) throws Exception {

        ApiSecurityErrorHandler errorHandler = new ApiSecurityErrorHandler(errorWriter);

        http
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(csrfTokenRequestHandler))
                /*
                 * 未登录时不保存"原本想去哪"再登录后跳回：那套机制服务的是服务端渲染的表单登录，
                 * 这里跳转由前端路由带 redirect 参数完成。留着它只会让匿名请求平白建会话。
                 */
                .requestCache(RequestCacheConfigurer::disable)
                .securityContext(context -> context.securityContextRepository(securityContextRepository))
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, AuthController.LOGIN_PATH).permitAll()
                        .requestMatchers(HttpMethod.GET, AuthController.SESSION_PATH).permitAll()
                        .requestMatchers(PUBLIC_HEALTH_PATTERNS).permitAll()
                        .requestMatchers(PUBLIC_UI_PATTERNS).permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errorHandler)
                        .accessDeniedHandler(errorHandler));

        return http.build();
    }
}
