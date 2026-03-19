package org.jeecg.modules.device.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.dto.SlamBindingPageQueryDTO;
import org.jeecg.modules.device.dto.SlamMapDetailDTO;
import org.jeecg.modules.device.dto.SlamMapPageQueryDTO;
import org.jeecg.modules.device.dto.SlamSyncStartDTO;
import org.jeecg.modules.device.dto.SlamSyncTaskDetailDTO;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.service.IIotSlamService;
import org.jeecg.modules.device.service.security.IDeviceSecurityService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/slam")
@RequiredArgsConstructor
@Tag(name = "SLAM管理", description = "SLAM地图上传许可、绑定关系、同步任务管理")
public class SlamController {

    private final IIotSlamService slamService;
    private final IDeviceSecurityService deviceSecurityService;

    @PostMapping("/maps/page")
    @Operation(summary = "分页查询SLAM地图")
    public ApiResult<IPage<IotSlamMap>> pageMaps(HttpServletRequest request, @RequestBody SlamMapPageQueryDTO query) {
        assertAdminOrOps(request);
        return ApiResult.ok(slamService.pageMaps(request.getHeader("x-tenant-id"), query));
    }

    @GetMapping("/maps/{id}")
    @Operation(summary = "SLAM地图详情")
    public ApiResult<SlamMapDetailDTO> mapDetail(HttpServletRequest request, @PathVariable String id) {
        assertAdminOrOps(request);
        return ApiResult.ok(slamService.mapDetail(id));
    }

    @PostMapping("/bindings/page")
    @Operation(summary = "分页查询机器人SLAM绑定关系")
    public ApiResult<IPage<IotRobotSlamBinding>> pageBindings(HttpServletRequest request,
                                                               @RequestBody SlamBindingPageQueryDTO query) {
        assertAdminOrOps(request);
        return ApiResult.ok(slamService.pageBindings(request.getHeader("x-tenant-id"), query));
    }

    @PostMapping("/sync/start")
    @Operation(summary = "开始同步SLAM到目标机器人")
    public ApiResult<String> startSync(HttpServletRequest request, @RequestBody SlamSyncStartDTO dto) {
        assertAdminOrOps(request);
        String username = safeUsername(request);
        String taskId = slamService.startSync(request.getHeader("x-tenant-id"), username, dto.getEnterpriseId(),
                dto.getSourceRobotId(), dto.getSlamMapId(), dto.getTargetRobotIds());
        return ApiResult.ok(taskId);
    }

    @GetMapping("/sync/{taskId}")
    @Operation(summary = "查询SLAM同步任务详情")
    public ApiResult<SlamSyncTaskDetailDTO> taskDetail(HttpServletRequest request, @PathVariable String taskId) {
        assertAdminOrOps(request);
        return ApiResult.ok(slamService.taskDetail(taskId));
    }

    private void assertAdminOrOps(HttpServletRequest request) {
        deviceSecurityService.assertAdminOrOps(safeUsername(request));
    }

    private String safeUsername(HttpServletRequest request) {
        try {
            return JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
            return null;
        }
    }
}

