package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.DeviceAuthApiService;
import org.jeecg.modules.device.dto.DeviceAuthDTO;
import org.jeecg.modules.device.dto.DeviceAuthDetailDTO;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 设备授权管理
 */
@RestController
@RequestMapping("/api/device/auth")
@RequiredArgsConstructor
@Tag(name = "设备授权管理", description = "设备授权增删改查与分页查询")
public class DeviceAuthController {

    private final DeviceAuthApiService deviceAuthApiService;

    @PostMapping("/page")
    @Operation(summary = "分页查询设备授权列表")
    public ApiResult<IPage<DeviceAuthDTO>> page(HttpServletRequest request,
                                                @RequestBody DeviceAuthQueryDTO query) {
        return ApiResult.ok(deviceAuthApiService.page(request, query));
    }

    @PostMapping("/add")
    @Operation(summary = "新增设备授权")
    public ApiResult<DeviceAuthDTO> create(@RequestBody DeviceAuthDTO dto,
                                           HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.create(request, dto));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改设备授权")
    public ApiResult<DeviceAuthDTO> update(@PathVariable String id,
                                           @RequestBody DeviceAuthDTO dto,
                                           HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.update(request, id, dto));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除设备授权（逻辑删除）")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        deviceAuthApiService.delete(request, id);
        return ApiResult.ok(null);
    }

    @GetMapping("/{id}")
    @Operation(summary = "设备授权详情")
    public ApiResult<DeviceAuthDetailDTO> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.detail(request, id));
    }

    @GetMapping("/options/tenants")
    @Operation(summary = "租户下拉列表")
    public ApiResult<List<OptionDTO>> tenantOptions(HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.tenantOptions(request));
    }

    @GetMapping("/options/tenants/{tenantId}/users")
    @Operation(summary = "租户下用户下拉列表")
    public ApiResult<List<OptionDTO>> tenantUserOptions(@PathVariable Integer tenantId, HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.tenantUserOptions(request, tenantId));
    }

    @GetMapping("/options/enterprises/{enterpriseId}/users")
    @Operation(summary = "企业用户下拉列表")
    public ApiResult<List<OptionDTO>> enterpriseUserOptions(@PathVariable("enterpriseId") String enterpriseId,
                                                            HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.enterpriseUserOptions(request, enterpriseId));
    }

    @GetMapping("/options/enterprises/tree")
    @Operation(summary = "企业下拉树")
    public ApiResult<List<OptionTreeDTO>> enterpriseOptionsTree(HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.enterpriseOptionsTree(request));
    }

    @GetMapping("/options/controllers/available")
    @Operation(summary = "可授权主控下拉列表")
    public ApiResult<List<OptionDTO>> availableControllers(HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.availableDevices(request, 2));
    }

    @GetMapping("/options/robots/available")
    @Operation(summary = "可授权机器人下拉列表")
    public ApiResult<List<OptionDTO>> availableRobots(HttpServletRequest request) {
        return ApiResult.ok(deviceAuthApiService.availableDevices(request, 1));
    }


    @PostMapping("/export")
    @Operation(summary = "导出授权列表Excel")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestBody DeviceAuthQueryDTO query) {
        return deviceAuthApiService.export(request, query);
    }
}

