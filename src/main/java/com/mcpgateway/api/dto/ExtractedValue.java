package com.mcpgateway.api.dto;

/**
 * 从入参或返回里按 JSON Pointer 抽出来的一个值（需求 FR-06.5 的列表可配置列）。
 *
 * <p>为什么不是一个裸字符串：抽不到值有好几种原因，而它们在排障时的含义完全不同 ——
 * "这条记录没有这个字段"和"正文太大，网关没去解析"会导致完全不同的下一步动作。
 * 只回一个 null，界面上就只能画一个"—"，操作人无从判断该改路径还是该点开详情。
 *
 * <p>{@code value} 最长 {@link com.mcpgateway.service.CallPayloadExtractor#MAX_VALUE_LENGTH}
 * 个字符，超出部分丢弃并把 {@code truncated} 置为 true。<b>正文本身永远不会出现在列表响应里</b>，
 * 见 SECURITY.md。
 *
 * @param value     抽到的值，state 不是 OK 时为 null
 * @param state     抽取结果
 * @param truncated 值是否被截断
 */
public record ExtractedValue(String value, State state, boolean truncated) {

    public enum State {
        /** 抽到了。 */
        OK,
        /** 正文为空，或路径在这条记录里不存在 —— 不同工具的参数结构本来就不一样，这是常态。 */
        MISSING,
        /** 正文不是合法 JSON（打点时序列化失败写下的占位符，或历史遗留的坏数据）。 */
        NOT_JSON,
        /** 正文超过解析上限，网关没有把它读进内存。要看内容请展开这一条。 */
        TOO_LARGE
    }

    public static ExtractedValue of(String value, boolean truncated) {
        return new ExtractedValue(value, State.OK, truncated);
    }

    public static ExtractedValue missing() {
        return new ExtractedValue(null, State.MISSING, false);
    }

    public static ExtractedValue notJson() {
        return new ExtractedValue(null, State.NOT_JSON, false);
    }

    public static ExtractedValue tooLarge() {
        return new ExtractedValue(null, State.TOO_LARGE, false);
    }
}
