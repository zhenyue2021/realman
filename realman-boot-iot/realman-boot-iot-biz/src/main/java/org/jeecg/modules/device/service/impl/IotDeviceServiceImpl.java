package org.jeecg.modules.device.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
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
import org.jeecg.modules.device.stream.ZlMediaKitPlayUrlClient;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.util.DeviceExcelExportUtil;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

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
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final DeviceCameraStreamPendingService deviceCameraStreamPendingService;
    private final ZlMediaKitPlayUrlClient      zlMediaKitPlayUrlClient;


    //    流媒体 MQTT 查询下发：host、push port、app（拉流 HTTPS 见 {@link ZlMediaKitPlayUrlClient}）
    @Value("${device.stream.host:172.16.44.66}")
    private String host;
    @Value("${device.stream.port.push:554}")
    private String pushPort;
    @Value("${device.stream.app:live}")
    private String app;

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
                request.getAuthEffectiveTime(),
                request.getAuthExpireTime(),
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
        if (dto.getDeviceName() != null) device.setMacAddress(dto.getMacAddress());
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
                requestDTO.getAuthEffectiveTime(),
                requestDTO.getAuthExpireTime(),
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DeviceCameraStreamVO> startTeleop(String controllerId, String robotId, String operator) {
        IotDevice controller = require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), 2)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        IotDevice robot = require(robotId);
        if (!Objects.equals(robot.getDeviceType(), 1)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }

        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            // 复用主控端当前应操作机器人 topic，不等待 ACK
            String topic = String.format(DeviceConstant.MqttTopic.TELEOP_ROBOT_ASSIGN, controller.getDeviceCode());
            MqttMessageModel.RobotAssignCommand assignCmd = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(robot.getDeviceCode())
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(controller.getDeviceCode(), topic,
                    objectMapper.writeValueAsString(assignCmd), 1);

            // 机器人状态置为使用中
            robot.setStatus(DeviceConstant.DeviceStatus.IN_USE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controller.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "开始遥操：通知主控关联机器人", "{commandId:" + commandId + ",robotCode:" + robot.getDeviceCode() + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robot.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "开始遥操：设备状态置为使用中", "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
            // 获取机器人的摄像头视频流
            return getCameraStreams(robot.getId(), null);
        } catch (Exception e) {
            throw new RuntimeException("开始遥操失败: " + e.getMessage(), e);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stopTeleop(String controllerId, String robotId, String robotCode, String operator) {
        IotDevice controller = require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), 2)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        String controllerDeviceCode = controller.getDeviceCode();

        IotDevice robot;
        if (robotId != null && !robotId.isBlank()) {
            robot = require(robotId);
        } else if (robotCode != null && !robotCode.isBlank()) {
            robot = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, robotCode)
                    .eq(IotDevice::getDelFlag, 0)
                    .last("LIMIT 1"));
        } else {
            throw new RuntimeException("deviceId 或 deviceCode 至少传一个");
        }
        if (robot == null || !Objects.equals(robot.getDeviceType(), 1)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }
        String robotDeviceCode = robot.getDeviceCode();

        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            // 1) 通知主控停止遥操（ 传 STOP 标识）
            String controllerTopic = String.format(DeviceConstant.MqttTopic.DEVICE_STOP_CONTROL, controllerDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForController = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(controllerDeviceCode)
                    .workOrderId("STOP")
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(controllerDeviceCode, controllerTopic,
                    objectMapper.writeValueAsString(stopForController), 1);

            // 2) 通知机器人停止遥操
            String robotTopic = String.format(DeviceConstant.MqttTopic.MASTER_STOP_CONTROL, robotDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForRobot = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(robotDeviceCode)
                    .workOrderId("stop_teleop")
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(robotDeviceCode, robotTopic,
                    objectMapper.writeValueAsString(stopForRobot), 1);

            // 3) 不等待ACK，直接将机器人状态置为在线
            robot.setStatus(DeviceConstant.DeviceStatus.ONLINE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controllerDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：通知主控与机器人", "{commandId:" + commandId + ",robotCode:" + robotDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：设备状态置为在线", "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
        } catch (Exception e) {
            throw new RuntimeException("停止遥操失败: " + e.getMessage(), e);
        }
    }

    /**
     * 向机器人设备下发力反馈参数指令（机械臂/夹爪力度）
     *
     * <p>注意：此处仅负责通过 MQTT 下发指令并记录操作日志，不同步等待 ACK。
     * ACK 由 DeviceCommandAckHandler 统一记录。
     */
    public String sendRobotForceFeedbackCommand(IotDevice robot,
                                                Integer armLevel,
                                                Integer gripperLevel,
                                                String operator) {
        if (DeviceConstant.DeviceStatus.ONLINE != robot.getStatus()) {
            throw new RuntimeException("机器人设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            MqttMessageModel.DeviceForceFeedbackCommand cmd = MqttMessageModel.DeviceForceFeedbackCommand.builder()
                    .commandId(commandId)
                    .armLevel(armLevel)
                    .gripperLevel(gripperLevel)
                    .timestamp(now)
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.DEVICE_FORCE_FEEDBACK, robot.getDeviceCode());
            mqttPublisher.publishToDevice(robot.getDeviceCode(), topic, payload, 1);

            String desc = "设置力反馈参数: armLevel=" + armLevel + ", gripperLevel=" + gripperLevel;
            logService.recordLog(robot.getId(), robot.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);

            // 记录到 iot_device_config（指令已下发，syncStatus=0 待设备 ACK）
            upsertDeviceConfig(robot.getId(), robot.getDeviceCode(), "arm_level",
                    armLevel == null ? null : armLevel.toString(), "force_feedback");
            upsertDeviceConfig(robot.getId(), robot.getDeviceCode(), "gripper_level",
                    gripperLevel == null ? null : gripperLevel.toString(), "force_feedback");
        } catch (Exception e) {
            throw new RuntimeException("发送力反馈指令失败: " + e.getMessage(), e);
        }
        return commandId;
    }

    /**
     * 向主控设备下发运动与安全参数指令（底盘速度/升降速度）
     */
    public String sendMasterSportSpeedCommand(IotDevice controller,
                                              Integer moveSpeedLevel,
                                              Integer liftSpeedLevel,
                                              String operator) {
        if (DeviceConstant.DeviceStatus.ONLINE != controller.getStatus()) {
            throw new RuntimeException("主控设备不在线");
        }
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            MqttMessageModel.MasterSportSpeedCommand cmd = MqttMessageModel.MasterSportSpeedCommand.builder()
                    .commandId(commandId)
                    .moveSpeedLevel(moveSpeedLevel)
                    .liftSpeedLevel(liftSpeedLevel)
                    .timestamp(now)
                    .build();
            String payload = objectMapper.writeValueAsString(cmd);
            String topic = String.format(DeviceConstant.MqttTopic.MASTER_SPORT_SPEED, controller.getDeviceCode());
            mqttPublisher.publishToDevice(controller.getDeviceCode(), topic, payload, 1);

            String desc = "设置运动与安全参数: moveSpeedLevel=" + moveSpeedLevel + ", liftSpeedLevel=" + liftSpeedLevel;
            logService.recordLog(controller.getId(), controller.getDeviceCode(),
                    DeviceConstant.OperationType.COMMAND_SEND,
                    desc, "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);

            // 记录到 iot_device_config（指令已下发，syncStatus=0 待设备 ACK）
            upsertDeviceConfig(controller.getId(), controller.getDeviceCode(), "move_speed_level",
                    moveSpeedLevel == null ? null : moveSpeedLevel.toString(), "sport_speed");
            upsertDeviceConfig(controller.getId(), controller.getDeviceCode(), "lift_speed_level",
                    liftSpeedLevel == null ? null : liftSpeedLevel.toString(), "sport_speed");
        } catch (Exception e) {
            throw new RuntimeException("发送运动与安全参数指令失败: " + e.getMessage(), e);
        }
        return commandId;
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
     *   <li>阻塞等待机器人响应（最长 10 秒），由 {@link DeviceCameraStreamResponseHandler} 处理
     *       {@code device/{deviceCode}/camera/stream/ack} 并完成 Future</li>
     *   <li>将响应中的 {@link MqttMessageModel.CameraInfo} 列表转换为 {@link DeviceCameraStreamVO} 列表返回</li>
     * </ol>
     *
     * @param deviceId    设备 ID
     * @param cameraIndex 指定摄像头路数索引，null 表示查询全部，非 null 表示查询单路
     * @return 摄像头流信息列表（controller 端可根据 cameraIndex 选择返回单路或多路）
     */
    @Override
    public List<DeviceCameraStreamVO> getCameraStreams(String deviceId, Integer cameraIndex) {
        List<DeviceCameraStreamVO> result = new ArrayList<>();
        List<DeviceCameraStreamVO> sourceResult = new ArrayList<>();
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
                    .host(host)
                    .port(pushPort)
                    .app(app)
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
            log.info("设备 {} 摄像头流查询结果：{}", deviceId, JSON.toJSONString(cameras));
            sourceResult =  cameras.stream()
                    .map(c -> DeviceCameraStreamVO.builder()
                            .cameraIndex(c.getCameraIndex())
                            .cameraName(c.getCameraName())
                            .streamUrl(c.getStream())
                            .build())
                    .collect(Collectors.toList());
        } catch (java.util.concurrent.TimeoutException e) {
            deviceCameraStreamPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("等待摄像头流地址超时（10s），设备未响应");
        } catch (Exception e) {
            throw new RuntimeException("获取摄像头流地址失败: " + e.getMessage(), e);
        }
        if (CollectionUtil.isNotEmpty(sourceResult)) {
            sourceResult.forEach(c -> {
                if (StrUtil.isNotBlank(c.getStreamUrl())) {
                    String playUrl = zlMediaKitPlayUrlClient.resolveHlsPlayUrlIfStreamOnline(c.getStreamUrl());
                    result.add(DeviceCameraStreamVO.builder()
                            .cameraIndex(c.getCameraIndex())
                            .cameraName(c.getCameraName())
                            .streamUrl(playUrl != null ? playUrl : c.getStreamUrl())
                            .build());
                } else {
                    result.add(c);
                }
            });
        }
        return result;
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


    @Override
    public Map<String, IotDeviceAuth> loadTenantAuth(List<String> deviceIds, String tenantId, String deviceType) {
        if (deviceIds == null || deviceIds.isEmpty() || tenantId == null || tenantId.isBlank()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        LambdaQueryWrapper<IotDeviceAuth> iotDeviceAuthLambdaQueryWrapper = new LambdaQueryWrapper<IotDeviceAuth>()
                .eq(IotDeviceAuth::getTenantId, tenantId)
                .eq(IotDeviceAuth::getStatus, 1)
                .eq(IotDeviceAuth::getDelFlag, 0)
                .and(w -> w.isNull(IotDeviceAuth::getEffectiveTime).or().le(IotDeviceAuth::getEffectiveTime, now))
                .and(w -> w.isNull(IotDeviceAuth::getExpireTime).or().ge(IotDeviceAuth::getExpireTime, now))
                .orderByDesc(IotDeviceAuth::getEffectiveTime);
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.CONTROLLER)) {
            iotDeviceAuthLambdaQueryWrapper.in(IotDeviceAuth::getControllerId, deviceIds);
        }
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.ROBOT)) {
            iotDeviceAuthLambdaQueryWrapper.in(IotDeviceAuth::getDeviceId, deviceIds);
        }
        List<IotDeviceAuth> auths = deviceAuthMapper.selectList(iotDeviceAuthLambdaQueryWrapper);
        if (auths == null || auths.isEmpty()) {
            return Map.of();
        }
        // 多条时取 effective_time 最新的一条
        if (Objects.equals(deviceType, DeviceConstant.DeviceType.CONTROLLER)) {
            return auths.stream()
                    .filter(a -> a.getControllerId() != null)
                    .collect(Collectors.toMap(IotDeviceAuth::getControllerId, a -> a, (a, b) -> a));
        } else if (Objects.equals(deviceType, DeviceConstant.DeviceType.ROBOT)) {
            return auths.stream()
                    .filter(a -> a.getDeviceId() != null)
                    .collect(Collectors.toMap(IotDeviceAuth::getDeviceId, a -> a, (a, b) -> a));
        }
        return Map.of();
    }

    /**
     * 将参数配置写入 iot_device_config（存在则更新，不存在则插入）
     *
     * @param deviceId   设备 ID
     * @param deviceCode 设备编码
     * @param configKey  配置键，如 "arm_level"
     * @param configValue 配置值（统一转 String 存储）
     * @param configType 配置分类，如 "force_feedback" / "sport_speed"
     */
    private void upsertDeviceConfig(String deviceId, String deviceCode,
                                    String configKey, String configValue, String configType) {
        IotDeviceConfig existing = configMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<IotDeviceConfig>()
                        .eq(IotDeviceConfig::getDeviceId, deviceId)
                        .eq(IotDeviceConfig::getConfigKey, configKey)
                        .last("LIMIT 1")
        );
        LocalDateTime now = LocalDateTime.now();
        if (existing != null) {
            existing.setConfigValue(configValue);
            existing.setConfigType(configType);
            existing.setSyncStatus(0);   // 0=待确认，指令已下发但设备尚未 ACK
            existing.setSyncTime(now);
            configMapper.updateById(existing);
        } else {
            IotDeviceConfig config = new IotDeviceConfig();
            config.setDeviceId(deviceId);
            config.setDeviceCode(deviceCode);
            config.setConfigKey(configKey);
            config.setConfigValue(configValue);
            config.setConfigType(configType);
            config.setSyncStatus(0);
            config.setSyncTime(now);
            configMapper.insert(config);
        }
    }
}
