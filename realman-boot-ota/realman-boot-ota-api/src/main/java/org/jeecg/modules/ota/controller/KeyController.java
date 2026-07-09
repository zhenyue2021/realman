package org.jeecg.modules.ota.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.ota.service.IOtaKeyService;
import org.jeecg.modules.ota.util.RequestUtil;
import org.jeecg.modules.ota.vo.KeyDTO;
import org.jeecg.modules.ota.vo.KeyListQuery;
import org.jeecg.modules.ota.vo.KeyRevokeRequest;
import org.jeecg.modules.ota.vo.KeyUploadRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Ed25519 公钥生命周期管理，对齐 OTA 平台详细设计四章（PRD 4.2.2、9.3）。 */
@RestController
@RequiredArgsConstructor
@Tag(name = "签名公钥管理", description = "上传/查询/激活/吊销")
public class KeyController {

    private final IOtaKeyService keyService;

    @PostMapping("/api/v1/ota-keys")
    @RequiresPermissions("ota:key:upload")
    @Operation(summary = "上传公钥（PRD 9.3.1）")
    public Result<KeyDTO> upload(@Valid @RequestBody KeyUploadRequest request, HttpServletRequest httpRequest) {
        return Result.ok(keyService.upload(request, RequestUtil.safeUsername(httpRequest)));
    }

    @GetMapping("/api/v1/ota-keys")
    @Operation(summary = "查询公钥列表（PRD 9.3.2）")
    public Result<PageResult<KeyDTO>> list(KeyListQuery query) {
        return Result.ok(keyService.list(query));
    }

    @PutMapping("/api/v1/ota-keys/{id}/activate")
    @RequiresPermissions("ota:key:activate")
    @Operation(summary = "激活 pending_activation 公钥（PRD 9.3.3）")
    public Result<KeyDTO> activate(@PathVariable("id") String keyId, HttpServletRequest httpRequest) {
        return Result.ok(keyService.activate(keyId, RequestUtil.safeUsername(httpRequest)));
    }

    @PutMapping("/api/v1/ota-keys/{id}/revoke")
    @RequiresPermissions("ota:key:revoke")
    @Operation(summary = "紧急吊销公钥，须输入确认文本 REVOKE（PRD 9.3.4）")
    public Result<KeyDTO> revoke(@PathVariable("id") String keyId, @Valid @RequestBody KeyRevokeRequest request,
                                  HttpServletRequest httpRequest) {
        return Result.ok(keyService.revoke(keyId, request, RequestUtil.safeUsername(httpRequest)));
    }
}
