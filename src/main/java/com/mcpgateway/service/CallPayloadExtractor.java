package com.mcpgateway.service;

import com.fasterxml.jackson.core.JsonPointer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.api.dto.ExtractedValue;
import com.mcpgateway.error.ErrorCode;
import com.mcpgateway.error.GatewayException;
import com.mcpgateway.repository.ToolCallRecordRepository.PayloadSlice;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 列表可配置列里的"正文抽取"（需求 FR-06.5）。
 *
 * <p>列表接口原本一个字节的正文都不返回，理由写在 SECURITY.md：一次请求捞不走成批的业务内容。
 * 但排障时最想在列表上直接看到的恰恰是正文里的一两个字段（"这次问的是什么"、"返回的头一段是什么"），
 * 逐条展开去找非常慢。这个类是那条约束的受控开口，三道闸：
 *
 * <ol>
 *   <li><b>只回抽到的值，不回正文。</b>每个值最长 {@value #MAX_VALUE_LENGTH} 个字符，
 *       一行最多 {@value #MAX_SPECS} 个值 —— 一页 100 条的上限也就 80 KB 量级，
 *       而原始正文单条就可能接近 1 MiB。</li>
 *   <li><b>必须显式请求。</b>不带 extract 参数时行为与从前完全一致。</li>
 *   <li><b>超大正文不解析。</b>见 {@link #MAX_PAYLOAD_CHARS}，既是内存保护，
 *       也让"能被成批抽取的内容"有个明确的天花板。</li>
 * </ol>
 *
 * <p>路径用 JSON Pointer（RFC 6901，Jackson 原生支持）而不是自己造一套语法：
 * {@code /q}、{@code /content/0/text}。空串表示整个文档，等于一个截断到 200 字符的预览列。
 */
@Component
public class CallPayloadExtractor {

    /** 一次查询最多几个抽取列。四个已经够用，再多列表也读不过来。 */
    public static final int MAX_SPECS = 4;

    /** 单个值的最大字符数。 */
    public static final int MAX_VALUE_LENGTH = 200;

    /**
     * 参与解析的正文上限，字符。
     *
     * 超过就直接标 TOO_LARGE，不读进内存 —— 一页 100 条、每条两个字段，
     * 上限乘起来才是这次请求真正的内存占用。
     */
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** 抽取的来源。 */
    public enum Source {
        REQUEST,
        RESPONSE
    }

    /**
     * 一个抽取列。
     *
     * @param key     回给前端的键，形如 {@code request:/q}；规范化过，大小写和原样输入无关
     * @param source  从入参还是返回里抽
     * @param pointer JSON Pointer，空 pointer 表示整个文档
     */
    public record Spec(String key, Source source, JsonPointer pointer) {
    }

    private final ObjectMapper objectMapper;

    public CallPayloadExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 解析 {@code extract} 参数，形如 {@code request:/q}、{@code response:/content/0/text}。
     *
     * <p>写错了直接报错而不是静默忽略：静默忽略会让操作人对着一整列"—"以为是数据里没有这个字段，
     * 而实际上是路径根本没生效。
     *
     * @throws GatewayException INVALID_REQUEST，参数格式不对或超过数量上限
     */
    public List<Spec> parse(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }

        // 去重按规范化后的 key 算：同一个路径写两遍只是多占一列，没有意义
        Map<String, Spec> specs = new LinkedHashMap<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            Spec spec = parseOne(item.trim());
            specs.putIfAbsent(spec.key(), spec);
        }
        if (specs.size() > MAX_SPECS) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "extract accepts at most " + MAX_SPECS + " fields");
        }
        return List.copyOf(specs.values());
    }

    private static Spec parseOne(String item) {
        int separator = item.indexOf(':');
        if (separator < 0) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "extract must look like request:/field or response:/content/0/text");
        }

        String rawSource = item.substring(0, separator).trim().toUpperCase(Locale.ROOT);
        Source source;
        try {
            source = Source.valueOf(rawSource);
        }
        catch (IllegalArgumentException ex) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "extract source must be request or response");
        }

        String rawPointer = item.substring(separator + 1).trim();
        if (rawPointer.length() > MAX_VALUE_LENGTH) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "extract pointer is too long");
        }
        if (!rawPointer.isEmpty() && !rawPointer.startsWith("/")) {
            // 最常见的手误：把 JSON Pointer 写成了点号路径
            throw GatewayException.of(ErrorCode.INVALID_REQUEST,
                    "extract pointer must be a JSON Pointer starting with /, for example /content/0/text");
        }

        JsonPointer pointer;
        try {
            pointer = JsonPointer.compile(rawPointer);
        }
        catch (IllegalArgumentException ex) {
            throw GatewayException.of(ErrorCode.INVALID_REQUEST, "extract pointer is not a valid JSON Pointer");
        }
        return new Spec(source.name().toLowerCase(Locale.ROOT) + ":" + rawPointer, source, pointer);
    }

    /**
     * 从一条记录的正文里抽出每一列的值。
     *
     * <p>一条记录的入参和返回各自只解析一次，不会因为配了四列就把同一段 JSON 解析四遍。
     */
    public Map<String, ExtractedValue> extract(List<Spec> specs, PayloadSlice slice) {
        if (specs.isEmpty()) {
            return Map.of();
        }

        Map<Source, ParsedPayload> parsed = new LinkedHashMap<>();
        Map<String, ExtractedValue> values = new LinkedHashMap<>();
        for (Spec spec : specs) {
            ParsedPayload payload = parsed.computeIfAbsent(spec.source(),
                    source -> parsePayload(slice, source));
            values.put(spec.key(), payload.valueAt(spec.pointer()));
        }
        return values;
    }

    private ParsedPayload parsePayload(PayloadSlice slice, Source source) {
        if (slice == null) {
            return ParsedPayload.of(ExtractedValue.missing());
        }
        boolean tooLarge = source == Source.REQUEST ? slice.requestTooLarge() : slice.responseTooLarge();
        if (tooLarge) {
            return ParsedPayload.of(ExtractedValue.tooLarge());
        }

        String json = source == Source.REQUEST ? slice.requestJson() : slice.responseJson();
        if (json == null || json.isBlank()) {
            return ParsedPayload.of(ExtractedValue.missing());
        }
        try {
            return new ParsedPayload(this.objectMapper.readTree(json), null);
        }
        catch (Exception ex) {
            // 打点时序列化失败会写 {"_unserializable":true}，历史遗留的坏数据也可能解析不了。
            // 解析不了是事实，不是错误 —— 不能让一条坏记录把整页查询打挂。
            return ParsedPayload.of(ExtractedValue.notJson());
        }
    }

    /** 解析结果：要么是一棵树，要么是一个"为什么没有树"的结论。 */
    private record ParsedPayload(JsonNode tree, ExtractedValue failure) {

        static ParsedPayload of(ExtractedValue failure) {
            return new ParsedPayload(null, failure);
        }

        ExtractedValue valueAt(JsonPointer pointer) {
            if (this.tree == null) {
                return this.failure;
            }
            JsonNode node = this.tree.at(pointer);
            if (node.isMissingNode() || node.isNull()) {
                return ExtractedValue.missing();
            }
            return render(node);
        }

        /**
         * 值转成一行文本。
         *
         * 对象和数组按紧凑 JSON 输出而不是拒绝显示：路径指到一个子对象上往往是故意的
         * （"我就想看看这层长什么样"），给一段被截断的 JSON 比给一个"—"有用。
         */
        private static ExtractedValue render(JsonNode node) {
            String text = node.isValueNode() ? node.asText() : node.toString();
            if (text.length() <= MAX_VALUE_LENGTH) {
                return ExtractedValue.of(text, false);
            }
            return ExtractedValue.of(text.substring(0, MAX_VALUE_LENGTH), true);
        }
    }

    /** 一页记录的抽取结果，按 callId 索引。 */
    public Map<String, Map<String, ExtractedValue>> extractAll(List<Spec> specs,
            Map<String, PayloadSlice> slices) {
        if (specs.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, ExtractedValue>> byCallId = new LinkedHashMap<>();
        List<String> callIds = new ArrayList<>(slices.keySet());
        for (String callId : callIds) {
            byCallId.put(callId, extract(specs, slices.get(callId)));
        }
        return byCallId;
    }
}
