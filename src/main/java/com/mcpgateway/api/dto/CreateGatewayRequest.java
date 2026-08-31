package com.mcpgateway.api.dto;

import com.mcpgateway.domain.Gateway;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 需求 6.1.1 - 6.1.3。 */
public record CreateGatewayRequest(

        @NotBlank(message = "必填")
        @Size(max = Gateway.MAX_NAME_LENGTH, message = "长度不得超过 64")
        String name,

        @NotBlank(message = "必填")
        @Pattern(regexp = Gateway.SLUG_PATTERN, message = "只能包含字母、数字、短横线和下划线，长度 1-64")
        String slug,

        @Size(max = Gateway.MAX_DESCRIPTION_LENGTH, message = "长度不得超过 4000")
        String description) {
}
