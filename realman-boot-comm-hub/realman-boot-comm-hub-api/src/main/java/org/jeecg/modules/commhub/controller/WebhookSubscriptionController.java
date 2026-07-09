package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.service.IWebhookSubscriptionService;
import org.jeecg.modules.commhub.util.RequestUtil;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateRequest;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionDTO;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionListQuery;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上行事件 Webhook 订阅管理，见设备通信中台详细设计 4.3.2。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "上行事件 Webhook 订阅", description = "第三方业务后台订阅设备上行事件推送")
public class WebhookSubscriptionController {

    private final IWebhookSubscriptionService webhookSubscriptionService;

    @PostMapping("/api/v1/webhook-subscriptions")
    @RequiresPermissions("commHub:webhookSubscription:add")
    @Operation(summary = "创建 Webhook 订阅")
    public Result<WebhookSubscriptionCreateResult> create(@RequestBody @Valid WebhookSubscriptionCreateRequest request,
                                                            HttpServletRequest httpRequest) {
        return Result.ok(webhookSubscriptionService.create(request, RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/webhook-subscriptions")
    @Operation(summary = "查询 Webhook 订阅")
    public Result<PageResult<WebhookSubscriptionDTO>> list(WebhookSubscriptionListQuery query) {
        return Result.ok(webhookSubscriptionService.list(query));
    }

    @DeleteMapping("/api/v1/webhook-subscriptions/{id}")
    @RequiresPermissions("commHub:webhookSubscription:delete")
    @Operation(summary = "停用 Webhook 订阅")
    public Result<Void> disable(@PathVariable String id) {
        webhookSubscriptionService.disable(id);
        return Result.ok();
    }
}
