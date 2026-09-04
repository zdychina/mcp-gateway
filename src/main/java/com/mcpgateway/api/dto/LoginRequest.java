package com.mcpgateway.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * 管理端登录请求。
 *
 * 刻意收 JSON 而不是表单：Spring Security 自带的 formLogin 吃的是
 * application/x-www-form-urlencoded，而"管理接口只收 application/json"是
 * SECURITY.md 里明写的一道跨站保护，不能为了少写一个控制器就打掉它。
 *
 * 这个记录里的口令是明文，只在一次请求的生命周期内存在：AuthController 立刻把它交给
 * AuthenticationManager，之后不再引用，也绝不进日志、不进调用记录、不进异常文案。
 */
public record LoginRequest(

        @NotBlank(message = "必填")
        String username,

        @NotBlank(message = "必填")
        String password) {
}
