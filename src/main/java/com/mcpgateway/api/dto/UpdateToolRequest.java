package com.mcpgateway.api.dto;

import jakarta.validation.constraints.Size;

/**
 * 修改工具的启用状态或自定义描述（需求 §8 的 PATCH 接口）。
 *
 * 这是 PATCH 语义，两个字段都可以不传表示不改。难点在自定义描述上：
 * "不传"和"传空串要清除"必须区分开，而 record 做不到这一点 —— 两种情况下字段值都是 null。
 * 所以这里用普通类 + setter 记录字段是否出现过，Jackson 只在 JSON 里真有这个键时才调 setter。
 */
public class UpdateToolRequest {

    /** null 表示不改动启用状态。 */
    private Boolean enabled;

    @Size(max = 4000, message = "长度不得超过 4000")
    private String customDescription;

    private boolean customDescriptionPresent;

    public Boolean enabled() {
        return this.enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String customDescription() {
        return this.customDescription;
    }

    public void setCustomDescription(String customDescription) {
        this.customDescription = customDescription;
        this.customDescriptionPresent = true;
    }

    public String getCustomDescription() {
        return this.customDescription;
    }

    /** JSON 里出现过 customDescription 这个键（哪怕值是 null 或空串）。 */
    public boolean customDescriptionPresent() {
        return this.customDescriptionPresent;
    }
}
