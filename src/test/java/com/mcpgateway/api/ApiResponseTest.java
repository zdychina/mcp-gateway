package com.mcpgateway.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ApiResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("成功响应带数据、error 为空")
    void successCarriesDataAndNoError() {
        ApiResponse<Map<String, String>> response = ApiResponse.ok(Map.of("id", "g1"));

        assertThat(response.success()).isTrue();
        assertThat(response.data()).containsEntry("id", "g1");
        assertThat(response.error()).isNull();
    }

    @Test
    @DisplayName("无内容的成功响应 data 为空")
    void successWithoutBody() {
        ApiResponse<Void> response = ApiResponse.ok();

        assertThat(response.success()).isTrue();
        assertThat(response.data()).isNull();
        assertThat(response.error()).isNull();
    }

    @Test
    @DisplayName("失败响应携带稳定错误码，data 为空")
    void failureCarriesStableCode() {
        ApiResponse<Void> response = ApiResponse.fail(ErrorCode.MCP_SERVER_LIMIT_EXCEEDED, "至多 3 个子 MCP");

        assertThat(response.success()).isFalse();
        assertThat(response.data()).isNull();
        assertThat(response.error().code()).isEqualTo("MCP_SERVER_LIMIT_EXCEEDED");
        assertThat(response.error().message()).isEqualTo("至多 3 个子 MCP");
    }

    @Test
    @DisplayName("需求 §8：序列化后 success / data / error 三个字段恒定存在")
    void serialisesAllThreeFields() throws Exception {
        String json = this.objectMapper.writeValueAsString(ApiResponse.ok());

        assertThat(json).contains("\"success\"").contains("\"data\"").contains("\"error\"");
        assertThat(this.objectMapper.readTree(json).has("error")).isTrue();
        assertThat(this.objectMapper.readTree(json).get("error").isNull()).isTrue();
    }
}
