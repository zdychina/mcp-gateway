package com.mcpgateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mcpgateway.api.dto.GatewayDetailResponse;
import com.mcpgateway.service.GatewayService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * 管理前端的页面入口（需求 §10）。
 *
 * 页面本身由 Thymeleaf 服务端渲染，所有变更走已有的 REST API：
 * 渲染逻辑只有一份，前端 JS 只负责发请求和刷新，不重复实现一套视图模型。
 *
 * 注意这里没有登录和权限（需求 3.2），部署上只能靠网络边界保护（需求 12.8）。
 */
@Controller
public class GatewayPageController {

    private final GatewayService gatewayService;

    private final ObjectMapper objectMapper;

    public GatewayPageController(GatewayService gatewayService, ObjectMapper objectMapper) {
        this.gatewayService = gatewayService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/")
    public String home() {
        return "redirect:/ui/gateways";
    }

    /** 需求 10.1：网关列表页。 */
    @GetMapping("/ui/gateways")
    public String list(Model model) {
        model.addAttribute("gateways", this.gatewayService.list());
        return "gateways";
    }

    /** 需求 10.2：网关详情页。 */
    @GetMapping("/ui/gateways/{gatewayId}")
    public String detail(@PathVariable String gatewayId, Model model) {
        GatewayDetailResponse gateway = this.gatewayService.detail(gatewayId);
        model.addAttribute("gateway", gateway);
        model.addAttribute("agentConfigJson", prettyAgentConfig(gatewayId));
        return "gateway-detail";
    }

    /**
     * 需求 FR-05：可复制的接入 JSON。缩进过的版本更适合直接粘进 Agent 的配置文件。
     */
    private String prettyAgentConfig(String gatewayId) {
        try {
            return this.objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(this.gatewayService.agentConfig(gatewayId));
        }
        catch (Exception ex) {
            // 展示用的东西不该让整个页面打不开。
            return "{}";
        }
    }
}
