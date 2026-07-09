package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.service.IApiKeyService;
import org.jeecg.modules.commhub.util.RequestUtil;
import org.jeecg.modules.commhub.vo.ApiKeyCreateRequest;
import org.jeecg.modules.commhub.vo.ApiKeyCreateResult;
import org.jeecg.modules.commhub.vo.ApiKeyDTO;
import org.jeecg.modules.commhub.vo.ApiKeyListQuery;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP-MQTT 桥接第三方系统 API Key 管理（运维/超管使用），见设备通信中台详细设计 4.3.1/4.5。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "桥接 API Key 管理", description = "第三方系统身份的设备/Topic 授权范围")
public class ApiKeyController {

    private final IApiKeyService apiKeyService;

    @PostMapping("/api/v1/api-keys")
    @RequiresPermissions("commHub:apiKey:create")
    @Operation(summary = "创建 API Key（原始值仅本次返回）")
    public Result<ApiKeyCreateResult> create(@RequestBody @Valid ApiKeyCreateRequest request, HttpServletRequest httpRequest) {
        return Result.ok(apiKeyService.create(request, RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/api-keys")
    @Operation(summary = "查询 API Key 台账")
    public Result<PageResult<ApiKeyDTO>> list(ApiKeyListQuery query) {
        return Result.ok(apiKeyService.list(query));
    }

    @DeleteMapping("/api/v1/api-keys/{id}")
    @RequiresPermissions("commHub:apiKey:revoke")
    @Operation(summary = "吊销 API Key")
    public Result<Void> revoke(@PathVariable String id) {
        apiKeyService.revoke(id);
        return Result.ok();
    }
}
