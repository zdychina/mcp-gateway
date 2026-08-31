package com.mcpgateway.mcpserver;

import io.modelcontextprotocol.server.McpStatelessServerHandler;
import io.modelcontextprotocol.spec.McpStatelessServerTransport;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.UnaryOperator;

/**
 * 夹在 SDK server 和真实 transport 之间的一层，唯一的作用是截住 SDK 装进来的默认 handler。
 *
 * 为什么需要它：{@code McpServer.sync(transport).build()} 内部会调用
 * {@code transport.setMcpHandler(defaultHandler)}，而真实 transport 只提供 setter、没有 getter，
 * 拿不到那个默认 handler 就没法装饰它。把 setter 拦下来是唯一不改 SDK 就能拿到它的办法。
 *
 * 接口本身只有两个抽象方法，所以这层很薄。
 */
public class CapturingStatelessTransport implements McpStatelessServerTransport {

    private final McpStatelessServerTransport delegate;

    private final UnaryOperator<McpStatelessServerHandler> decorator;

    public CapturingStatelessTransport(McpStatelessServerTransport delegate,
            UnaryOperator<McpStatelessServerHandler> decorator) {
        this.delegate = delegate;
        this.decorator = decorator;
    }

    @Override
    public void setMcpHandler(McpStatelessServerHandler handler) {
        this.delegate.setMcpHandler(this.decorator.apply(handler));
    }

    @Override
    public Mono<Void> closeGracefully() {
        return this.delegate.closeGracefully();
    }

    @Override
    public void close() {
        this.delegate.close();
    }

    @Override
    public List<String> protocolVersions() {
        return this.delegate.protocolVersions();
    }
}
