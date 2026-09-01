package com.mcpgateway.web;

import com.mcpgateway.AbstractApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 管理前端的入口路由（需求 §10）。
 *
 * 页面内容本身不在这里验 —— 它全部由 Vue 渲染，服务端返回的只是一个不含任何数据的空壳。
 * 需求 10.1 / 10.2 的验收断言在 frontend/test/ 下，随 mvn verify 一起跑。
 *
 * 这一层要守住的是：所有前端路由地址都能拿到 SPA 入口文档，
 * 直接访问、刷新和后退都不会 404。
 */
class GatewayPageControllerTest extends AbstractApiTest {

    private static final String SPA_ENTRY = "/app/index.html";

    @Test
    @DisplayName("根路径重定向到网关列表")
    void rootRedirectsToTheList() throws Exception {
        this.mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/ui/gateways"));
    }

    @Test
    @DisplayName("需求 10.1：列表页地址转发到 SPA 入口")
    void listPageForwardsToTheSpaEntry() throws Exception {
        this.mockMvc.perform(get("/ui/gateways"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(SPA_ENTRY));
    }

    /**
     * 需求 10.2：详情页是前端路由，服务端不认识 {id}，一律给空壳。
     *
     * 这里刻意用一个不存在的 id：在 Thymeleaf 时代这个请求返回 404，
     * 迁到 SPA 之后变成 200 + 空壳，由前端取 /api/gateways/{id} 拿到
     * GATEWAY_NOT_FOUND 后自己渲染"没有找到这个网关"。行为变了，记在这里。
     */
    @Test
    @DisplayName("需求 10.2：详情页地址转发到 SPA 入口，网关不存在也一样")
    void detailPageForwardsToTheSpaEntry() throws Exception {
        this.mockMvc.perform(get("/ui/gateways/{id}", "no-such-gateway"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(SPA_ENTRY));
    }

    @Test
    @DisplayName("任意前端路由地址都能刷新 —— 深层路径同样给空壳，而不是 404")
    void anyFrontendRouteIsForwarded() throws Exception {
        for (String path : new String[] { "/ui", "/ui/", "/ui/gateways/abc/anything" }) {
            this.mockMvc.perform(get(path))
                    .andExpect(status().isOk())
                    .andExpect(forwardedUrl(SPA_ENTRY));
        }
    }

    /**
     * /ui/** 是个通配映射，必须确认它没有把管理 API 一起吃掉 ——
     * 那会让所有接口返回 HTML 空壳而不是 JSON，且不会有任何测试变红。
     */
    @Test
    @DisplayName("通配映射不吃掉 /api 和 /app 下的请求")
    void wildcardDoesNotSwallowApiOrAssets() throws Exception {
        this.mockMvc.perform(get("/api/gateways"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl(null));

        this.mockMvc.perform(get("/api/no-such-endpoint"))
                .andExpect(status().isNotFound());

        // Vue 产物必须真的在 —— 少跑一次前端构建就会在这里失败，
        // 而不是等用户打开页面看到白屏
        this.mockMvc.perform(get(SPA_ENTRY)).andExpect(status().isOk());
    }
}
