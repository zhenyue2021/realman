package org.jeecg.modules.device.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.device.dto.DeviceProvisionRequestDTO;
import org.jeecg.modules.device.dto.DeviceProvisionResponseDTO;
import org.jeecg.modules.device.service.IDeviceProvisionService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 设备 HTTP 自注册接口（设备上电后、连接 MQTT 之前调用）。
 *
 * <p>安全说明：通过 MD5 签名校验请求完整性；建议 Nginx IP 白名单限制仅内网/设备网段访问。
 *
 * <pre>
 * POST /realman-iot/internal/device/provision
 * {
 *   "deviceCode": "RM-2026000123",
 *   "macAddress": "AA:BB:CC:DD:EE:FF",
 *   "deviceModel": "RM-X1",
 *   "deviceType": "1",
 *   "deviceName": "机器人-001",
 *   "description": "可选备注",
 *   "timestamp": 1710000000000,
 *   "sign": "md5-hex-uppercase"
 * }
 * </pre>
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/device")
@RequiredArgsConstructor
public class DeviceProvisionController {

    private final IDeviceProvisionService provisionService;

    @IgnoreAuth
    @PostMapping("/provision")
    @Operation(summary = "设备 HTTP 自注册（Provision）")
    public ApiResult<DeviceProvisionResponseDTO> provision(@Valid @RequestBody DeviceProvisionRequestDTO request) {
        log.info("[Provision] 收到注册请求 deviceType={} deviceCode={} mac={}",
                request.getDeviceType(), request.getDeviceCode(), request.getMacAddress());
        try {
            DeviceProvisionResponseDTO response = provisionService.provision(request);
            String msg = response.isNewlyRegistered() ? "设备注册成功" : "设备已注册";
            return ApiResult.ok(response, msg);
        } catch (IllegalArgumentException | IllegalStateException e) {
            log.warn("[Provision] 注册失败 deviceCode={} reason={}", request.getDeviceCode(), e.getMessage());
            return ApiResult.fail(e.getMessage());
        }
    }
}
