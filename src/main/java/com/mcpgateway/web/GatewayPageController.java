package com.mcpgateway.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 管理前端的入口（需求 §10）。
 *
 * 页面本身全部由 Vue 渲染（见 frontend/），这个控制器只负责把 /ui/** 的请求
 * 转发给 SPA 的入口文档 —— 数据一律走 /api 下的管理接口。
 *
 * 需求 10.1 / 10.2 那两个页面的验收断言相应地在 frontend/test/ 下，
 * 由 mvn verify 的 npm-test execution 执行。
 *
 * 注意这里没有登录和权限（需求 3.2），部署上只能靠网络边界保护（需求 12.8）。
 */
@Controller
public class GatewayPageController {

    /**
     * Vue 应用的入口文档。产物由 Vite 打进 static/app/，随 jar 一起发布 ——
     * 与后端同源，因此不需要放开任何 CORS。这一点是刻意的：管理端没有登录也没有
     * CSRF 令牌，"接口只收 JSON + 没有 CORS 响应头"就是当前仅有的跨站保护。
     */
    private static final String SPA_ENTRY = "forward:/app/index.html";

    @GetMapping("/")
    public String home() {
        return "redirect:/ui/gateways";
    }

    /**
     * 前端路由的所有地址都落到同一个入口文档上。
     *
     * 用通配而不是逐条列出，是为了让直接访问、刷新和后退都能work —— 浏览器请求的是
     * /ui/gateways/{id} 这种真实路径，服务端必须都给出 SPA 空壳，由前端路由去解析。
     * 新增页面时这里不需要跟着改。
     *
     * 注意 /api/** 和 /app/** 都不在这个前缀下，不会被它吃掉。
     */
    @GetMapping({ "/ui", "/ui/**" })
    public String spa() {
        return SPA_ENTRY;
    }
}
