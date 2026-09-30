package com.mcpgateway.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * 编辑单个子 MCP。
 *
 * headers 为 null 表示"保持原样"，为空对象表示"清空"。这两者必须区分开：
 * 前端拿到的是遮罩值，如果把遮罩值原样提交回来会把真凭证覆盖成 ******。
 *
 * customDescription 是 PUT 全量语义：null 或空白表示清除、回退到捕获的原始描述。
 * 这与工具的 PATCH 三态（不传 = 不改）有意不同 —— 本接口本来就是全量提交，
 * 客户端每次都要把完整表单发上来，漏传字段即清除覆盖。
 */
public record UpdateDownstreamRequest(

        @NotBlank(message = "必填")
        @Size(max = 64, message = "长度不得超过 64")
        String name,

        @NotBlank(message = "必填")
        String url,

        Map<String, String> headers,

        @Size(max = 4000, message = "长度不得超过 4000")
        String customDescription) {
}
