package org.jeecg.modules.device.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.entity.*;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.handler.DeviceCameraStreamResponseHandler;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.util.DeviceExcelExportUtil;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 设备管理 Service 实现
 *
 * <p>核心职责：
 * <ul>
 *   <li>设备生命周期管理（注册/禁用/启用）</li>
 *   <li>参数配置下发与同步状态跟踪</li>
 *   <li>实时状态查询（Redis 优先，DB 降级）</li>
 *   <li>远程重启指令下发（MQTT + AES 加密）</li>
 *   <li>在线状态批量查询</li>
 * </ul>
 *
 * <p>鉴权说明：
 *   设备通过 MD5(deviceCode) 作为 MQTT 密码直连 EMQX，无需应用层登录。
 *   下行消息使用 Per-Device AES-256 密钥加密（密钥由 deviceCode 通过 SHA256 派生）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceServiceImpl extends ServiceImpl<IotDeviceMapper, IotDevice>
        implements IIotDeviceService {

    private final IotDeviceMapper            deviceMapper;
    private final IotDeviceConfigMapper      configMapper;
    private final IotDeviceStatusMapper      statusMapper;
    private final DeviceSecretService        secretService;
    private final CommandEncryptService      encryptService;
    private final MqttPublisher              mqttPublisher;
    private final StringRedisTemplate        redisTemplate;
    private final ObjectMapper               objectMapper;
    private final IDeviceOperationLogService logService;
    private final DeviceCameraStreamPendingService deviceCameraStreamPendingService;

    /**
     * 注册新设备
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验 deviceCode 唯一性</li>
     *   <li>设置初始状态为 INACTIVE（等待首次上线）</li>
     *   <li>自动生成 deviceSecret = MD5(deviceCode)，并写入 Redis 缓存 24h</li>
     *   <li>插入设备记录到 DB</li>
     *   <li>记录设备注册操作日志</li>
     * </ol>
     *
     * @param device 设备基础信息（需包含 deviceCode）
     * @return 插入后的设备对象（含 id 和 deviceSecret）
     * @throws RuntimeException deviceCode 已存在时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDevice addDevice(IotDevice device) {
        // 1. 校验 deviceCode 唯一性
        long cnt = deviceMapper.selectCount(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, device.getDeviceCode()));
        if (cnt > 0) throw new RuntimeException("设备编号已存在: " + device.getDeviceCode());

        // 2. 初始化状态
        device.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        device.setCreateTime(LocalDateTime.now());

        // 3. 生成 deviceSecret = MD5(deviceCode)，同时预热 Redis 缓存
        String generateSecret = secretService.generateSecret(device.getDeviceCode());
        device.setDeviceSecret(generateSecret);

        // 4. 持久化
        deviceMapper.insert(device);

        // 5. 记录操作日志
        logService.recordLog(device.getId(), device.getDeviceCode(),
                DeviceConstant.OperationType.DEVICE_REGISTER,
                "新设备注册: " + device.getDeviceCode(), null,
                DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, null, null);
        return device;
    }

    /**
     * 分页查询设备列表（带权限控制）
     *
     * <p>权限规则：
     * <ul>
     *   <li>超级管理员（admin）可查看全部设备</li>
     *   <li>普通用户只能查看与自己关联（通过设备授权表）的设备</li>
     *   <li>多租户场景下通过 tenantId 进一步过滤</li>
     * </ul>
     *
     * @param page       分页参数
     * @param request    查询条件（设备名/类型/状态/产品ID/时间范围/当前用户信息）
     * @return 分页结果
     */
    @Override
    public IPage<IotDevice> queryDevicePage(Page<IotDevice> page, DeviceRequestDTO request) {
        return deviceMapper.selectDeviceList(
                page,
                request.getDeviceName(),
                request.getDeviceType(),
                request.getStatus(),
                request.getProductId(),
                request.getStartTime(),
                request.getEndTime(),
                request.getCurrentUsername(),
                request.getCurrentTenantId(),
                request.getSuperAdmin()
        );
    }

    /**
     * 编辑设备（仅更新 DTO 中非空字段，deviceCode/deviceType 不可改）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDevice(String deviceId, DeviceUpdateDTO dto) {
        IotDevice device = require(deviceId);
        if (dto.getDeviceName() != null) device.setDeviceName(dto.getDeviceName());
        if (dto.getDeviceModel() != null) device.setDeviceModel(dto.getDeviceModel());
        if (dto.getSerialNumber() != null) device.setSerialNumber(dto.getSerialNumber());
        if (dto.getDescription() != null) device.setDescription(dto.getDescription());
        if (dto.getLongitude() != null) device.setLongitude(dto.getLongitude());
        if (dto.getLatitude() != null) device.setLatitude(dto.getLatitude());
        deviceMapper.updateById(device);
    }

    @Override
    public byte[] exportDeviceList(DeviceRequestDTO requestDTO) {
        int max = DeviceExcelExportUtil.getMaxExportRows();
        IPage<IotDevice> page = deviceMapper.selectDeviceList(
                new Page<>(1, max),
                requestDTO.getDeviceName(),
                requestDTO.getDeviceType(),
                requestDTO.getStatus(),
                requestDTO.getProductId(),
                requestDTO.getStartTime(),
                requestDTO.getEndTime(),
                requestDTO.getCurrentUsername(),
                requestDTO.getCurrentTenantId(),
                requestDTO.getSuperAdmin()
        );
        try {
            return DeviceExcelExportUtil.exportDevices(page.getRecords());
        } catch (Exception e) {
            throw new RuntimeException("导出Excel失败", e);
        }
    }

    /**
     * 设置并同步设备参数配置
     *
     * <p>执行流程：
     * <ol>
     *   <li>查询设备基础信息（验证存在性）</li>
     *   <li>生成本次同步 commandId（用于 ACK 关联）</li>
     *   <li>遍历参数：已有配置则更新，没有则新增，均标记为 PENDING</li>
     *   <li>若设备在线：立即加密推送 ConfigPush 消息，并写入 Redis 等待 ACK Key（TTL=30s）</li>
     *   <li>若设备离线：仅保存到 DB，待上线后由 PendingSyncService 补推</li>
     * </ol>
     *
     * @param deviceId 设备 ID
     * @param params   参数键值对（configKey → configValue）
     * @throws RuntimeException 设备不存在或在线时 MQTT 推送失败时抛出
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAndSyncConfig(String deviceId, Map<String, Object> params) {
        IotDevice device = require(deviceId);
        String commandId = IdUtil.fastSimpleUUID();

        // 逐条 upsert 配置记录（存在则更新，不存在则新增），统一标记为 PENDING
        params.forEach((key, value) -> {
            IotDeviceConfig cfg = configMapper.selectOne(new LambdaQueryWrapper<IotDeviceConfig>()
                    .eq(IotDeviceConfig::getDeviceId, deviceId)
                    .eq(IotDeviceConfig::getConfigKey, key));
            if (cfg != null) {
                cfg.setConfigValue(String.valueOf(value));
                cfg.setSyncStatus(DeviceConstant.ConfigSyncStatus.PENDING);
                configMapper.updateById(cfg);
            } else {
                cfg = new IotDeviceConfig();
                cfg.setDeviceId(deviceId);
                cfg.setDeviceCode(device.getDeviceCode());
                cfg.setConfigKey(key);
                cfg.setConfigValue(String.valueOf(value));
                cfg.setConfigType("string");
                cfg.setSyncStatus(DeviceConstant.ConfigSyncStatus.PENDING);
                cfg.setCreateTime(LocalDateTime.now());
                cfg.setUpdateTime(LocalDateTime.now());
                configMapper.insert(cfg);
            }
        });

        if (DeviceConstant.DeviceStatus.ONLINE == device.getStatus()) {
            // 设备在线：立即加密推送
            try {
                String payload = objectMapper.writeValueAsString(MqttMessageModel.ConfigPush.builder()
                        .commandId(commandId).params(params).timestamp(System.currentTimeMillis()).build());
                mqttPublisher.publishToDevice(device.getDeviceCode(),
                        String.format(DeviceConstant.MqttTopic.CONFIG_PUSH, device.getDeviceCode()), payload, 1);
                // 写入 ACK 等待 Key，超时后自然过期（设备未响应不阻塞后续逻辑）
                redisTemplate.opsForValue().set(
                        DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + device.getDeviceCode() + ":" + commandId,
                        "pending", DeviceConstant.Timeout.CONFIG_SYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException("配置推送失败: " + e.getMessage());
            }
        } else {
            // 设备离线：配置已持久化，待上线后补推
            log.warn("[Config] 设备[{}]离线，配置已保存待上线后同步", device.getDeviceCode());
        }
    }

    /**
     * 获取设备实时监控状态
     *
     * <p>数据源策略（优先级从高到低）：
     * <ol>
     *   <li>Redis 实时缓存（设备最近一次上报，TTL≤离线阈值）→ dataSource="realtime"</li>
     *   <li>DB 最近一条历史状态记录（Redis Key 过期说明已超时）→ dataSource="database"</li>
     * </ol>
     *
     * @param deviceId 设备 ID
     * @return 包含 deviceId/deviceCode/status/lastOnlineTime 和传感器数据的 Map
     */
    @Override
    public Map<String, Object> getDeviceMonitorStatus(String deviceId) {
        IotDevice device = require(deviceId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deviceId",       deviceId);
        result.put("deviceCode",     device.getDeviceCode());
        result.put("status",         device.getStatus());
        result.put("lastOnlineTime", device.getLastOnlineTime());

        // 优先取 Redis 实时状态
        String cached = redisTemplate.opsForValue().get(
                DeviceConstant.RedisKey.DEVICE_STATUS_PREFIX + device.getDeviceCode());
        if (cached != null) {
            try {
                Map<String, Object> cachedMap = objectMapper.readValue(
                        cached, new TypeReference<Map<String, Object>>() {});
                result.putAll(cachedMap);
                result.put("dataSource", "realtime");
            } catch (Exception ignored) {
            }
        } else {
            // 降级：从 DB 取最近一条历史记录
            IotDeviceStatus s = statusMapper.selectOne(new LambdaQueryWrapper<IotDeviceStatus>()
                    .eq(IotDeviceStatus::getDeviceId, deviceId)
                    .orderByDesc(IotDeviceStatus::getReceiveTime).last("LIMIT 1"));
            if (s != null) {
                result.put("temperature",    s.getTemperature());
                result.put("humidity",       s.getHumidity());
                result.put("batteryLevel",   s.getBatteryLevel());
                result.put("signalStrength", s.getSignalStrength());
                result.put("longitude",      s.getLongitude());
                result.put("latitude",       s.getLatitude());
                result.put("runStatus",      s.getRunStatus());
                result.put("reportTime",     s.getReportTime());
                result.put("dataSource",     "database");
            }
        }
        return result;
    }

    /**
     * 获取设备详情聚合视图（管理平台详情页使用）
     *
     * <p>聚合内容：
     * <ol>
     *   <li>设备基础信息（iot_device 表）</li>
     *   <li>在线状态（Redis Set 中是否存在）</li>
     *   <li>实时状态（Redis 优先，DB 降级）</li>
     *   <li>最近一条 DB 状态记录（用于详情页补全字段）</li>
     *   <li>最后心跳时间（优先取 realtime.timestamp，降级取 DB reportTime/receiveTime）</li>
     *   <li>最近 20 条操作日志</li>
     * </ol>
     *
     * @param deviceId 设备 ID
     * @return 聚合视图对象 {@link DeviceDetailVO}
     */
    @Override
    public DeviceDetailVO getDeviceDetail(String deviceId) {
        IotDevice device = require(deviceId);

        // 1. 从 Redis 在线集合判断实时在线状态
        boolean online = Boolean.TRUE.equals(redisTemplate.opsForSet()
                .isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, device.getDeviceCode()));

        // 2. 获取实时状态（Redis 优先，DB 降级）
        Map<String, Object> realtime = getDeviceMonitorStatus(deviceId);

        // 3. DB 最近一条状态记录（用于详情页字段兜底）
        IotDeviceStatus latest = statusMapper.selectOne(new LambdaQueryWrapper<IotDeviceStatus>()
                .eq(IotDeviceStatus::getDeviceId, deviceId)
                .orderByDesc(IotDeviceStatus::getReceiveTime).last("LIMIT 1"));

        // 4. 心跳时间：优先取 realtime 中的 timestamp 字段（毫秒），降级取 DB 时间
        LocalDateTime heartbeat = null;
        if (realtime != null) {
            Object ts = realtime.get("timestamp");
            if (ts instanceof Number n) {
                heartbeat = LocalDateTime.ofInstant(Instant.ofEpochMilli(n.longValue()), ZoneId.systemDefault());
            } else if (ts instanceof String s) {
                try {
                    long ms = Long.parseLong(s);
                    heartbeat = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault());
                } catch (Exception ignored) {
                }
            }
        }
        if (heartbeat == null && latest != null) {
            heartbeat = latest.getReportTime() != null ? latest.getReportTime() : latest.getReceiveTime();
        }

        // 5. 设备参数配置列表（按 device_id 查询）
        List<IotDeviceConfig> deviceConfigs = configMapper.selectList(
                new LambdaQueryWrapper<IotDeviceConfig>()
                        .eq(IotDeviceConfig::getDeviceId, deviceId)
                        .orderByAsc(IotDeviceConfig::getConfigKey));

        // 6. 最近 20 条操作日志
        List<IotDeviceOperationLog> recentLogs = logService
                .queryLogPage(new Page<>(1, 20), deviceId, null, null, null)
                .getRecords();

        // 7. 组装 VO
        DeviceDetailVO vo = new DeviceDetailVO();
        vo.setDevice(device);
        vo.setOnline(online);
        vo.setRealtimeStatus(realtime);
        vo.setDeviceConfigs(deviceConfigs);
        vo.setLatestStatus(latest);
        vo.setLastHeartbeatTime(heartbeat);
        vo.setRecentLogs(recentLogs);
        return vo;
    }

    @Override
    public String sendCommand(String deviceId, String cmd, String reason, String operator) {
        IotDevice device = require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        try {
            String topic = String.format("device/%s/command/%s", device.getDeviceCode(), cmd);
            Object body;
            long now = System.currentTimeMillis();
            switch (cmd) {
                case "restart" -> body = MqttMessageModel.RemoteRestartCommand.builder()
                        .commandId(commandId).reason(reason).timestamp(now).build();
                case "emergency-stop" -> body = MqttMessageModel.EmergencyStopCommand.builder()
                        .commandId(commandId).reason(reason).timestamp(now).build();
                default -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("commandId", commandId);
                    m.put("cmd", cmd);
                    m.put("reason", reason);
                    m.put("timestamp", now);
                    body = m;
                }
            }
            String payload = objectMapper.writeValueAsString(body);
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, 1);

            String opType = mapCommandToOperationType(cmd);
            String desc = "发送指令[" + cmd + "]" + (reason != null ? (": " + reason) : "");
            logService.recordLog(deviceId, device.getDeviceCode(),
                    opType,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("发送指令[" + cmd + "]失败: " + e.getMessage());
        }
        return commandId;
    }

    @Override
    public void remoteRestart(String deviceId, String reason, String operator) {
        sendCommand(deviceId, "restart", reason, operator);
    }

    @Override
    public void emergencyStop(String deviceId, String reason, String operator) {
        sendCommand(deviceId, "emergency-stop", reason, operator);
    }

    /**
     * 更改设备启用/禁用状态
     *
     * <p>禁用设备时，立即清除 Redis 中的密钥和 AES Key 缓存，
     * 使 EMQX 下次认证回调时从 DB 查询并拒绝连接（已连接的设备在下次心跳或重连时断线）。
     *
     * @param deviceId 设备 ID
     * @param status   目标状态（参考 DeviceConstant.DeviceStatus）
     * @param operator 操作人
     */
    @Override
    public void changeDeviceStatus(String deviceId, Integer status, String operator) {
        IotDevice device = require(deviceId);
        device.setStatus(status);
        deviceMapper.updateById(device);

        // 禁用时立即失效密钥缓存，使 EMQX 实时感知到设备被禁用
        if (DeviceConstant.DeviceStatus.DISABLED == status) {
            secretService.evict(device.getDeviceCode());
            encryptService.evictCache(device.getDeviceCode());
        }
    }

    private static String mapCommandToOperationType(String cmd) {
        if (cmd == null) return DeviceConstant.OperationType.COMMAND_SEND;
        return switch (cmd) {
            case "restart" -> DeviceConstant.OperationType.REMOTE_RESTART;
            case "emergency-stop" -> DeviceConstant.OperationType.EMERGENCY_STOP;
            case "poweroff" -> DeviceConstant.OperationType.POWER_OFF;
            case "reset" -> DeviceConstant.OperationType.RESET;
            default -> DeviceConstant.OperationType.COMMAND_SEND;
        };
    }

    /**
     * 批量查询设备在线状态
     *
     * <p>同时返回 DB 中的 status 字段和 Redis 实时在线集合中的状态，
     * 两者可能短暂不一致（DB 更新存在延迟），前端可据此显示"疑似在线"等状态。
     *
     * @param deviceIds 设备 ID 列表
     * @return 每个设备的 id/deviceCode/status/onlineInRedis/lastOnlineTime
     */
    @Override
    public List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds) {
        return deviceIds.stream().map(id -> {
            IotDevice d = deviceMapper.selectById(id);
            Map<String, Object> item = new LinkedHashMap<>();
            if (d != null) {
                boolean online = Boolean.TRUE.equals(redisTemplate.opsForSet()
                        .isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, d.getDeviceCode()));
                item.put("deviceId",      id);
                item.put("deviceCode",    d.getDeviceCode());
                item.put("status",        d.getStatus());
                item.put("onlineInRedis", online);
                item.put("lastOnlineTime", d.getLastOnlineTime());
            }
            return item;
        }).collect(Collectors.toList());
    }

    /**
     * 向机器人查询摄像头视频流地址（同步等待，超时 10 秒）
     *
     * <p>执行流程：
     * <ol>
     *   <li>校验设备存在且在线</li>
     *   <li>生成 commandId，调用 {@link DeviceCameraStreamPendingService#register(String)} 注册 Future</li>
     *   <li>构造 {@link MqttMessageModel.CameraStreamQuery}，通过 {@link MqttPublisher#publishToDevice} 发送到
     *       {@code device/{deviceCode}/camera/stream/query}（AES 加密）</li>
     *   <li>阻塞等待机器人响应（最长 10 秒），由 {@link DeviceCameraStreamResponseHandler} 完成 Future</li>
     *   <li>将响应中的 {@link MqttMessageModel.CameraInfo} 列表转换为 {@link DeviceCameraStreamVO} 列表返回</li>
     * </ol>
     *
     * @param deviceId    设备 ID
     * @param cameraIndex 指定摄像头路数索引，null 表示查询全部，非 null 表示查询单路
     * @return 摄像头流信息列表（controller 端可根据 cameraIndex 选择返回单路或多路）
     */
    @Override
    public List<DeviceCameraStreamVO> getCameraStreams(String deviceId, Integer cameraIndex) {
        IotDevice device = require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("设备不在线");
        }

        String commandId = IdUtil.fastSimpleUUID();
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future =
                deviceCameraStreamPendingService.register(commandId);

        try {
            MqttMessageModel.CameraStreamQuery query = MqttMessageModel.CameraStreamQuery.builder()
                    .commandId(commandId)
                    .cameraIndex(cameraIndex)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String payload = objectMapper.writeValueAsString(query);
            String topic = String.format(DeviceConstant.MqttTopic.CAMERA_STREAM_QUERY, device.getDeviceCode());
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, 1);
        } catch (Exception e) {
            deviceCameraStreamPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("摄像头流查询指令发送失败: " + e.getMessage(), e);
        }

        try {
            List<MqttMessageModel.CameraInfo> cameras = future.get(10, TimeUnit.SECONDS);
            return cameras.stream()
                    .map(c -> DeviceCameraStreamVO.builder()
                            .cameraIndex(c.getCameraIndex())
                            .cameraName(c.getCameraName())
                            .streamUrl(c.getStreamUrl())
                            .streamType(c.getStreamType())
                            .build())
                    .collect(Collectors.toList());
        } catch (java.util.concurrent.TimeoutException e) {
            deviceCameraStreamPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("等待摄像头流地址超时（10s），设备未响应");
        } catch (Exception e) {
            throw new RuntimeException("获取摄像头流地址失败: " + e.getMessage(), e);
        }
    }

    /**
     * 查询设备，不存在则抛出异常（统一防御性校验）
     *
     * @param deviceId 设备 ID
     * @return 设备对象
     * @throws RuntimeException 设备不存在时抛出
     */
    private IotDevice require(String deviceId) {
        IotDevice d = deviceMapper.selectById(deviceId);
        if (d == null) throw new RuntimeException("设备不存在: " + deviceId);
        return d;
    }
}
