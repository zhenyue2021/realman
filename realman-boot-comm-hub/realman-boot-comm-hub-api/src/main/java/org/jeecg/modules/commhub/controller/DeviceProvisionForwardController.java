package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceProvisionResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备端向唯一的 HTTP 例外：设备上电自注册。原样转发到设备管理业务平台，本层不做
 * 任何业务校验（校验、密钥/Token 签发都在设备管理业务平台完成），见设备通信中台
 * 详细设计 3.1、设备基座详细设计 3.5 注册流程时序图。
 */
@Hidden
@RestController
@RequiredArgsConstructor
@Tag(name = "设备自注册转发（内部）", description = "南向唯一 HTTP 例外：设备上电自注册")
public class DeviceProvisionForwardController {

    private final DeviceMgmtFeignClient deviceMgmtFeignClient;

    @PostMapping("/internal/device/provision")
    @Operation(summary = "设备上电自注册转发")
    public Result<DeviceProvisionResult> provision(@RequestBody @Valid DeviceProvisionRequest request) {
        return deviceMgmtFeignClient.provision(request);
    }
}
