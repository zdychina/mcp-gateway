package com.mcpgateway.api;

import com.mcpgateway.error.ErrorCode;

/**
 * 需求 §8 的统一响应结构：{@code {"success": true, "data": {}, "error": null}}。
 * 三个字段恒定存在（application.yml 里 jackson.default-property-inclusion=always）。
 */
public record ApiResponse<T>(boolean success, T data, ApiError error) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null);
    }

    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(true, null, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode code, String message) {
        return new ApiResponse<>(false, null, new ApiError(code.name(), message));
    }

    /** 失败时返回的错误体。不含堆栈，message 必须已脱敏。 */
    public record ApiError(String code, String message) {
    }
}
