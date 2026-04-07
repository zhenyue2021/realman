package org.jeecg.modules.device.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.service.IIotSlamCommandService;
import org.jeecg.modules.device.service.IIotSlamMapService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Objects;

/**
 * 建图 / 定位 / 导航指令管理
 *
 * <p>下行 Topic：device/{code}/slam/request
 * <p>上行 Topic：device/{code}/slam/ack、device/{code}/slam/states
 */
@RestController
@RequestMapping("/api/slam-command")
@RequiredArgsConstructor
@Tag(name = "SLAM 建图-定位-导航指令")
public class SlamCommandController {

    private final IIotSlamCommandService slamCommandService;
    private final IIotSlamMapService slamMapService;
    private final RedisUtil redisUtil;

    /**
     * 发送 SLAM 指令
     *
     * <p>请求体示例（切换模式）：
     * <pre>
     * {
     *   "functionName": "SwitchMode",
     *   "params": { "target_mode": "MappingAndLocalization" }
     * }
     * </pre>
     * <p>function 枚举值：SwitchMode / GetCurrentMap / SaveMap /
     * SinglePointNavigation / MultiWaypointNavigation / SetInitialPose
     */
    @PostMapping("/{masterCode}/send")
    @Operation(summary = "发送 SLAM 指令")
    public ApiResult<IotSlamCommandRecord> send(@PathVariable String masterCode,
                                                @RequestBody SlamCommandRequest body) {
        // body不能为空，body.getRobotCode() 机器人编码不能为空
        if (Objects.isNull(body)) {
            return ApiResult.fail("请求body不能为空");
        }
        if (StrUtil.isBlank(body.getRobotCode())) {
            return ApiResult.fail("robotCode-机器人编码不能为空");
        }
        if (StrUtil.isBlank(body.getFunctionName())) {
            return ApiResult.fail("function-功能名称不能为空");
        }
        IotSlamCommandRecord record = slamCommandService.sendCommand(
                masterCode, body.getRobotCode(), body.getFunctionName(), body.getParams());
        return ApiResult.ok(record);
    }

    /**
     * 分页查询指定设备的 SLAM 指令记录（按发送时间倒序）
     */
    @GetMapping("/{deviceCode}/records")
//    @Operation(summary = "分页查询 SLAM 指令请求/响应记录")
    public ApiResult<IPage<IotSlamCommandRecord>> records(
            @PathVariable String deviceCode,
            @RequestParam(defaultValue = "1") int pageNo,
            @RequestParam(defaultValue = "20") int pageSize) {
        IPage<IotSlamCommandRecord> page = slamCommandService.pageRecords(
                new Page<>(pageNo, pageSize), deviceCode);
        return ApiResult.ok(page);
    }

    /**
     * 查询指定设备的当前 SLAM 状态（来自 Redis 缓存）
     *
     * <p>设备每次上报 slam/states 时刷新，TTL=5min；超时未上报则返回 null。
     */
    @GetMapping("/{deviceCode}/states")
    @Operation(summary = "查询设备当前 SLAM 状态")
    public ApiResult<Object> states(@PathVariable String deviceCode) {
        String key = DeviceConstant.SlamRedisKey.SLAM_STATES_PREFIX + deviceCode;
        Object cached = redisUtil.get(key);
        return ApiResult.ok(cached);
    }

    /**
     * 查询机器人当前有效地图（自动刷新将过期的预签名 URL）
     *
     * <p>每次 GetCurrentMap 成功后异步写入，返回最新一条有效记录及可直接访问的预签名 URL。
     * 若从未上传或上传中则返回 null。
     */
    @GetMapping("/{robotCode}/current-map")
//    @Operation(summary = "查询机器人当前有效 SLAM 地图")
    public ApiResult<IotSlamMap> currentMap(@PathVariable String robotCode) {
        return ApiResult.ok(slamMapService.getCurrentMap(robotCode));
    }

    @Data
    public static class SlamCommandRequest {
        /** 功能代码，见 DeviceConstant.SlamFunction */
        @JsonAlias("function")
        private String functionName;
        // 机器人编码
        private String robotCode;
        /** 功能参数（依 function 不同结构不同，可为 null） */
        private Map<String, Object> params;
    }
}
