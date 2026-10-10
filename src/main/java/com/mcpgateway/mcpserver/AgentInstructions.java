package com.mcpgateway.mcpserver;

import com.mcpgateway.domain.DownstreamMcp;
import com.mcpgateway.domain.Gateway;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把网关描述和有描述的子 MCP 按模板组合成对外 MCP initialize 结果里的 instructions。
 *
 * MCP 协议只有服务级一个 instructions 通道，子 MCP 的说明只能并进来。拼接格式来自
 * 全局配置 {@code mcp-gateway.server.agent-instructions-template}（两个占位符：
 * {@code {{gatewayDescription}}} 和 {@code {{downstreams}}}），部署可以换掉标题、
 * 换语言、换结构；不配置时用 {@link #DEFAULT_TEMPLATE}。
 *
 * <p>两个刻意的规则：
 * <ul>
 * <li><b>默认模板渲染结果与旧版写死的拼接逐字节相同。</b> 已接入的 Agent 可能正在
 * 解析这段文案，升级不能改变一个字节 —— 旧断言（AgentInstructionsTest 的整串相等）
 * 就是这条规则的背书。</li>
 * <li><b>解析为空的占位符把它所在的段落整段丢掉</b>（段落 = 模板里被空行隔开的块）。
 * 这是"只有一侧有内容"时输出不残留悬空标题的关键：子 MCP 都没描述时，含
 * {@code {{downstreams}}} 的那一段连同段内的「子 MCP：」标题一起消失。</li>
 * </ul>
 *
 * <p>含未知或残缺 {@code {{...}}} 的模板在启动时被 {@link #validate} 拒绝，而不是
 * 静默当作字面文本发出去 —— 字面量泄漏进 instructions 是一种只能从 Agent 行为倒查
 * 的配置错误，越早炸越好。异常文案只回显出错的那个短 token，不回显整个模板，
 * 与 AdminAccount 对配置值的处理同理。
 */
public final class AgentInstructions {

    /**
     * 内置默认模板。渲染结果必须与 1.3 及更早写死的拼接完全一致：
     * 网关描述在前，空一行，「子 MCP：」标题带一行一条的清单。
     * GatewayProperties 的字段默认值引用这里，yml 里不重复出现这份文案。
     */
    public static final String DEFAULT_TEMPLATE = "{{gatewayDescription}}\n\n子 MCP：\n{{downstreams}}";

    /**
     * 占位符形态：{@code {{名字}}}，花括号内侧容忍空白（环境变量里手滑多敲一个
     * 空格不该让整份模板变成字面文本）。名字字符集与 DownstreamMcp.NAME_PATTERN
     * 对齐，够用且能把 {@code {{a b}}} 这类残缺形态排除在"未知名字"之外。
     */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.-]+)\\s*}}");

    private static final Set<String> KNOWN_PLACEHOLDERS = Set.of("gatewayDescription", "downstreams");

    /** 段落分隔 = 空行。切分前模板已在 effectiveTemplate 里归一成 \n 行尾。 */
    private static final Pattern PARAGRAPH_SEPARATOR = Pattern.compile("\\R\\s*\\R");

    private AgentInstructions() {
    }

    /**
     * 启动期校验（由 GatewayMcpRegistry 的构造器调用，坏模板 = 应用起不来）。
     * 空白模板合法 —— 等于用默认模板。
     *
     * @throws IllegalStateException 模板含未知或残缺的 {@code {{...}}}
     */
    public static void validate(String template) {
        String effective = effectiveTemplate(template);
        Matcher placeholders = PLACEHOLDER.matcher(effective);
        while (placeholders.find()) {
            if (!KNOWN_PLACEHOLDERS.contains(placeholders.group(1))) {
                throw unknownPlaceholder(placeholders.group(1));
            }
        }
        // 把合法占位符抠掉之后还残留 {{ 的，就是没写完或写坏了的开始标记
        //（比如 {{downstreams 少了右括号）——它们匹配不了上面的正则，只会被当字面文本发出去。
        if (PLACEHOLDER.matcher(effective).replaceAll("").contains("{{")) {
            throw new IllegalStateException(errorMessage("contains a malformed '{{' that opens no valid placeholder"));
        }
    }

    /**
     * 按模板组合 instructions。
     *
     * @param template 全局配置的模板；null/空白回退 {@link #DEFAULT_TEMPLATE}。
     *                 compose 与 {@link #validate} 走同一套检查，所以直接调用它
     *                 （比如测试）传坏模板也会炸而不是渲染出字面 {{。
     * @param downstreams 已按名称排序的子 MCP 列表（findByGatewayId 的自然顺序），
     *                    组合结果因此是确定性的
     * @return 组合后的 instructions；模板所有段落都被丢弃时返回 null
     */
    public static String compose(String template, Gateway gateway, List<DownstreamMcp> downstreams) {
        validate(template);

        // 渲染走"空串 = 段落丢弃"的语义，null 在这里就归一成空串。
        String gatewayValue = orEmpty(normalize(gateway.description()));
        String downstreamsValue = joinDownstreamEntries(downstreams);

        // 先按模板切段落、再逐段替换：描述内容里的换行（多行描述）不会制造假的段落分隔。
        StringBuilder rendered = new StringBuilder();
        for (String paragraph : PARAGRAPH_SEPARATOR.split(effectiveTemplate(template))) {
            String substituted = substituteParagraph(paragraph, gatewayValue, downstreamsValue);
            if (substituted != null) {
                if (rendered.length() > 0) {
                    rendered.append("\n\n");
                }
                rendered.append(substituted);
            }
        }
        String result = rendered.toString().trim();
        return result.isEmpty() ? null : result;
    }

    // ---------------------------------------------------------------- 内部

    /** 段落里有占位符解析为空 → 返回 null 表示整段丢弃；全空段落同样没有保留的价值。 */
    private static String substituteParagraph(String paragraph, String gatewayValue, String downstreamsValue) {
        if (paragraph.isBlank()) {
            return null;
        }
        Matcher placeholders = PLACEHOLDER.matcher(paragraph);
        StringBuilder substituted = new StringBuilder();
        while (placeholders.find()) {
            String value = resolvePlaceholder(placeholders.group(1), gatewayValue, downstreamsValue);
            if (value.isEmpty()) {
                return null;
            }
            // quoteReplacement：描述里的 $ 和 \ 对 appendReplacement 是转义语法，不引会被吃掉。
            placeholders.appendReplacement(substituted, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(substituted);
        return substituted.toString();
    }

    private static String resolvePlaceholder(String name, String gatewayValue, String downstreamsValue) {
        if ("gatewayDescription".equals(name)) {
            return gatewayValue;
        }
        if ("downstreams".equals(name)) {
            return downstreamsValue;
        }
        // compose 先走了 validate，正常到不了这里；留着它让本方法在任何输入下都是全函数。
        throw unknownPlaceholder(name);
    }

    /** 只列有生效描述的子 MCP：原始和自定义都为空的列出来只会是噪音。 */
    private static String joinDownstreamEntries(List<DownstreamMcp> downstreams) {
        List<String> entries = downstreams.stream()
                .map(downstream -> {
                    String description = normalize(downstream.effectiveDescription());
                    return description == null ? null
                            : "- " + downstream.name() + "：" + description;
                })
                .filter(Objects::nonNull)
                .toList();
        return entries.isEmpty() ? "" : String.join("\n", entries);
    }

    private static String effectiveTemplate(String template) {
        if (template == null || template.isBlank()) {
            return DEFAULT_TEMPLATE;
        }
        // Windows 环境变量会把 \r\n 带进模板，先统一成 \n 再分段：\R 允许把一个 \r\n
        // 拆成"\r + \n"两个换行匹配，不归一的话段内的单个 Windows 换行会被误判成段落分隔。
        return template.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private static IllegalStateException unknownPlaceholder(String name) {
        return new IllegalStateException(
                errorMessage("contains unknown placeholder '{{" + name + "}}'"));
    }

    private static String errorMessage(String detail) {
        return "MCP_GATEWAY_SERVER_AGENT_INSTRUCTIONS_TEMPLATE " + detail
                + "; supported placeholders are {{gatewayDescription}} and {{downstreams}}";
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
