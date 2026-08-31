package com.mcpgateway.mcpserver;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * slug 提取的边界。
 *
 * 这段值得单独钉死：SDK transport 内部只做 {@code requestURI.endsWith(messageEndpoint)}
 * 的后缀比较，像 /mcp/anything/mcp/real-slug 这样的路径能骗过它。精确匹配是唯一可靠的一道。
 */
class GatewayMcpDispatcherServletTest {

    @Test
    @DisplayName("正常的单段路径取出 slug")
    void extractsSingleSegmentSlug() {
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/my-gateway")).isEqualTo("my-gateway");
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/a")).isEqualTo("a");
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/gw_1")).isEqualTo("gw_1");
    }

    @Test
    @DisplayName("多段路径一律拒绝，包括能骗过 transport 后缀校验的嵌套写法")
    void rejectsNestedPaths() {
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/anything/mcp/real-slug")).isNull();
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/real-slug/extra")).isNull();
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/real-slug/")).isNull();
    }

    @Test
    @DisplayName("空路径和畸形路径返回 null")
    void rejectsEmptyOrMalformedPaths() {
        assertThat(GatewayMcpDispatcherServlet.extractSlug(null)).isNull();
        assertThat(GatewayMcpDispatcherServlet.extractSlug("")).isNull();
        assertThat(GatewayMcpDispatcherServlet.extractSlug("/")).isNull();
        assertThat(GatewayMcpDispatcherServlet.extractSlug("no-leading-slash")).isNull();
    }
}
