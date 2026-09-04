package com.mcpgateway.api.dto;

/**
 * 当前登录态。前端启动时拉一次，用于决定是渲染登录页还是主界面。
 *
 * 未登录时 username 为 null —— 这个接口是公开的，不能因为"顺便"就往里塞任何
 * 未登录者不该知道的东西（比如配置好的管理员用户名）。
 *
 * @param authenticated 是否已登录
 * @param username      已登录时的用户名，未登录时为 null
 */
public record SessionResponse(boolean authenticated, String username) {

    public static SessionResponse anonymous() {
        return new SessionResponse(false, null);
    }

    public static SessionResponse of(String username) {
        return new SessionResponse(true, username);
    }
}
