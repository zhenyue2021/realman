package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.service.IIotDeviceAuthService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.*;

/**
 * 设备授权管理
 */
@Slf4j
@RestController
@RequestMapping("/api/device/auth")
@RequiredArgsConstructor
@Tag(name = "设备授权管理", description = "设备授权增删改查与分页查询")
public class DeviceAuthController {

    private final IIotDeviceAuthService authService;

    @PostMapping("/page")
    @Operation(summary = "分页查询设备授权列表")
    public ApiResult<IPage<IotDeviceAuth>> page(HttpServletRequest request,
                                                @RequestBody DeviceAuthQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        String username = null;
        try {
            username = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        boolean superAdmin = "admin".equalsIgnoreCase(username);

        IPage<IotDeviceAuth> page = authService.queryAuthPage(
                new Page<>(pageNo, pageSize),
                query,
                username,
                superAdmin
        );
        return ApiResult.ok(page);
    }

    @PostMapping
    @Operation(summary = "新增设备授权")
    public ApiResult<IotDeviceAuth> create(@RequestBody IotDeviceAuth auth,
                                           HttpServletRequest request) {
        try {
            String username = JwtUtil.getUserNameByToken(request);
            auth.setCreateBy(username);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        authService.save(auth);
        return ApiResult.ok(auth);
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改设备授权")
    public ApiResult<IotDeviceAuth> update(@PathVariable String id,
                                           @RequestBody IotDeviceAuth auth,
                                           HttpServletRequest request) {
        auth.setId(id);
        try {
            String username = JwtUtil.getUserNameByToken(request);
            auth.setUpdateBy(username);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        authService.updateById(auth);
        return ApiResult.ok(auth);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除设备授权")
    public ApiResult<Void> delete(@PathVariable String id) {
        authService.removeById(id);
        return ApiResult.ok(null);
    }
}

