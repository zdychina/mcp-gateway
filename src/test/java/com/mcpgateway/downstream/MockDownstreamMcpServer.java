package com.mcpgateway.downstream;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport;
import io.modelcontextprotocol.spec.McpSchema;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 测试用的模拟子 MCP：真实的 Streamable HTTP 服务，工具集可以在运行中改变。
 *
 * 用真服务而不是 mock 客户端，是因为这一层要验证的恰恰是真实往返里才会发生的事情：
 * headers 有没有被送到下游、Schema 有没有在序列化里走样、连不上时抛的是什么异常。
 */
public class MockDownstreamMcpServer {

    private final String label;

    private final HttpServletStatelessServerTransport transport;

    private final McpStatelessSyncServer server;

    private final Set<String> currentTools = new LinkedHashSet<>();

    /** 每次请求收到的 Authorization 头，用于验证需求 6.2.6 的自定义 headers 确实生效。 */
    private final List<String> receivedAuthorizationHeaders = new CopyOnWriteArrayList<>();

    /** 收到的 tools/call 次数，用于验证停用的工具不会被转发。 */
    private final List<String> calledTools = new CopyOnWriteArrayList<>();

    public MockDownstreamMcpServer(String label, String path, List<String> initialTools) {
        this.label = label;
        this.transport = HttpServletStatelessServerTransport.builder()
                .messageEndpoint(path)
                .contextExtractor(request -> {
                    String authorization = request.getHeader("Authorization");
                    this.receivedAuthorizationHeaders.add(authorization == null ? "<none>" : authorization);
                    return McpTransportContext.EMPTY;
                })
                .build();

        this.server = McpServer.sync(this.transport)
                .serverInfo("mock-" + label, "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tools(initialTools.stream().map(this::toolSpec).toList())
                .build();
        this.currentTools.addAll(initialTools);
    }

    private McpStatelessServerFeatures.SyncToolSpecification toolSpec(String toolName) {
        return toolSpec(toolName, "original description of " + this.label + "." + toolName);
    }

    private McpStatelessServerFeatures.SyncToolSpecification toolSpec(String toolName, String description) {
        Map<String, Object> inputSchema = Map.of(
                "type", "object",
                "properties", Map.of("q", Map.of("type", "string")),
                "required", List.of("q"));

        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(toolName)
                .description(description)
                .inputSchema(inputSchema)
                .build();

        return new McpStatelessServerFeatures.SyncToolSpecification(tool, (context, request) -> {
            this.calledTools.add(toolName);
            Object q = request.arguments() == null ? null : request.arguments().get("q");
            return new McpSchema.CallToolResult(
                    List.of(new McpSchema.TextContent(this.label + "/" + toolName + ":" + q)), false, null, null);
        });
    }

    /** 把工具集替换成给定的这一批，模拟下游新增和删除工具。 */
    public void setTools(List<String> toolNames) {
        for (String removed : new HashSet<>(this.currentTools)) {
            if (!toolNames.contains(removed)) {
                this.server.removeTool(removed);
                this.currentTools.remove(removed);
            }
        }
        for (String added : toolNames) {
            if (this.currentTools.add(added)) {
                this.server.addTool(toolSpec(added));
            }
        }
    }

    /** 改掉某个工具的原始描述，模拟下游更新了工具定义。 */
    public void redescribe(String toolName, String description) {
        this.server.removeTool(toolName);
        this.server.addTool(toolSpec(toolName, description));
    }

    public HttpServletStatelessServerTransport transport() {
        return this.transport;
    }

    public List<String> receivedAuthorizationHeaders() {
        return List.copyOf(this.receivedAuthorizationHeaders);
    }

    public List<String> calledTools() {
        return List.copyOf(this.calledTools);
    }

    /**
     * 恢复到初始状态：清空记录，并把工具集连同**描述**一起重置。
     *
     * 描述必须一起重置 —— 这个 mock 是 Spring 单例，跨用例共享。只调 setTools 的话，
     * 已经存在的工具不会被重新注册，上一个用例 redescribe 出来的描述会残留到下一个用例。
     */
    public void reset(List<String> toolNames) {
        this.receivedAuthorizationHeaders.clear();
        this.calledTools.clear();
        for (String existing : new HashSet<>(this.currentTools)) {
            this.server.removeTool(existing);
        }
        this.currentTools.clear();
        setTools(toolNames);
    }
}
