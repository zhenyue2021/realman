package org.jeecg.modules.device.service.impl.device;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.WebRtcAckPendingService;
import org.jeecg.modules.device.service.signaling.SignalingKeyService;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceRoomVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 遥操作编排：设备校验、MQTT 指令下发、DB 状态更新、Redis 缓存清理。
 * 事务由调用方 {@link org.jeecg.modules.device.service.impl.IotDeviceServiceImpl} 统一控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceTeleopService {

    private static final int WEBRTC_ACK_TIMEOUT_SECONDS = 5;

    private final IotDeviceSupport deviceSupport;
    private final IotDeviceMapper deviceMapper;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;
    private final StringRedisTemplate redisTemplate;
    private final IotDeviceCameraStreamService cameraStreamService;
    private final IIotDeviceRoomService roomService;
    private final WebRtcAckPendingService webRtcAckPendingService;
    private final SignalingKeyService signalingKeyService;
    private final WebRtcProperties webRtcProperties;

    public List<DeviceCameraStreamVO> startTeleop(String controllerId, String robotId, String operator) {
        IotDevice controller = deviceSupport.require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DeviceTypeInteger.CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        IotDevice robot = deviceSupport.require(robotId);
        if (!Objects.equals(robot.getDeviceType(), DeviceConstant.DeviceTypeInteger.ROBOT)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }
        if (!Objects.equals(robot.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
            throw new RuntimeException("当前机器人不在线");
        }
        String controllerDeviceCode = controller.getDeviceCode();
        String robotDeviceCode = robot.getDeviceCode();
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            String topic = String.format(DeviceConstant.MqttTopic.TELEOP_ROBOT_ASSIGN, controllerDeviceCode);
            MqttMessageModel.RobotAssignCommand assignCmd = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(robotDeviceCode)
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(controllerDeviceCode, topic,
                    objectMapper.writeValueAsString(assignCmd), MqttConstant.MQTT_QOS.QOS_1);

            robot.setUseStatus(DeviceConstant.UseStatus.IN_USE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controllerDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "平台通知主控关联机器人", "{commandId:" + commandId + ",robotCode:" + robotDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "主控连接设备，设备使用状态置为占用", "{commandId:" + commandId + ",controllerDeviceCode:" + controllerDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
            return cameraStreamService.getCameraStreams(robot.getId(), null);
        } catch (Exception e) {
            throw new RuntimeException("开始遥操失败: " + e.getMessage(), e);
        }
    }

    public void startTeleopNoStream(String controllerId, String robotId, String operator) {
        IotDevice controller = deviceSupport.require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DeviceTypeInteger.CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        IotDevice robot = deviceSupport.require(robotId);
        if (!Objects.equals(robot.getDeviceType(), DeviceConstant.DeviceTypeInteger.ROBOT)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }
        if (!Objects.equals(robot.getStatus(), DeviceConstant.DeviceStatus.ONLINE)) {
            throw new RuntimeException("当前机器人不在线");
        }
        String controllerDeviceCode = controller.getDeviceCode();
        String robotDeviceCode = robot.getDeviceCode();
        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            String topic = String.format(DeviceConstant.MqttTopic.TELEOP_ROBOT_ASSIGN, controllerDeviceCode);
            MqttMessageModel.RobotAssignCommand assignCmd = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(robotDeviceCode)
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(controllerDeviceCode, topic,
                    objectMapper.writeValueAsString(assignCmd), MqttConstant.MQTT_QOS.QOS_1);

            robot.setUseStatus(DeviceConstant.UseStatus.IN_USE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controllerDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "平台通知主控关联机器人", "{commandId:" + commandId + ",robotCode:" + robotDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "主控连接设备，设备使用状态置为使用中", "{commandId:" + commandId + ",controllerDeviceCode:" + controllerDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
            sendWebRtcStartAndAwait(controllerDeviceCode, robotDeviceCode);
            roomService.robotJoin(controllerDeviceCode, robotDeviceCode);
        } catch (Exception e) {
            throw new RuntimeException("开始遥操失败: " + e.getMessage(), e);
        }
    }

    public void stopTeleop(String controllerId, String robotId, String robotCode, String operator) {
        IotDevice controller = deviceSupport.require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DeviceTypeInteger.CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        String controllerDeviceCode = controller.getDeviceCode();

        IotDevice robot;
        if (robotId != null && !robotId.isBlank()) {
            robot = deviceSupport.require(robotId);
        } else if (robotCode != null && !robotCode.isBlank()) {
            robot = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                    .eq(IotDevice::getDeviceCode, robotCode)
                    .eq(IotDevice::getDelFlag, 0)
                    .last("LIMIT 1"));
        } else {
            throw new RuntimeException("deviceId 或 deviceCode 至少传一个");
        }
        if (robot == null || !Objects.equals(robot.getDeviceType(), DeviceConstant.DeviceTypeInteger.ROBOT)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }
        String robotDeviceCode = robot.getDeviceCode();

        String commandId = IdUtil.fastSimpleUUID();
        long now = System.currentTimeMillis();
        try {
            String controllerTopic = String.format(DeviceConstant.MqttTopic.MASTER_STOP_CONTROL, controllerDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForController = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(controllerDeviceCode)
                    .workOrderId("STOP_MASTER")
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(controllerDeviceCode, controllerTopic,
                    objectMapper.writeValueAsString(stopForController), MqttConstant.MQTT_QOS.QOS_1);

            String robotTopic = String.format(DeviceConstant.MqttTopic.DEVICE_STOP_CONTROL, robotDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForRobot = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(commandId)
                    .robotCode(robotDeviceCode)
                    .workOrderId("STOP_SLAVE")
                    .timestamp(now)
                    .build();
            mqttPublisher.publishToDevice(robotDeviceCode, robotTopic,
                    objectMapper.writeValueAsString(stopForRobot), MqttConstant.MQTT_QOS.QOS_1);

            robot.setUseStatus(DeviceConstant.UseStatus.IDLE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controllerDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：通知主控与机器人", "{commandId:" + commandId + ",robotCode:" + robotDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：设备使用状态置为空闲", "{commandId:" + commandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
            redisTemplate.delete(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + controllerDeviceCode);
            redisTemplate.delete(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotDeviceCode);
            log.info("[TeleopCache] 清除遥操关系缓存: master={} robot={}", controllerDeviceCode, robotDeviceCode);
            sendWebRtcStop(robotDeviceCode);
            roomService.destroyRoom(controllerDeviceCode);
        } catch (Exception e) {
            throw new RuntimeException("停止遥操失败: " + e.getMessage(), e);
        }
    }

    // -------------------------------------------------------------------------
    // WebRTC 指令私有方法
    // -------------------------------------------------------------------------

    /**
     * 向机器人下发 WebRTC 开始指令，并同步等待 ACK（最多 {@value #WEBRTC_ACK_TIMEOUT_SECONDS} 秒）。
     *
     * <p>执行流程：
     * <ol>
     *   <li>从缓存获取/创建房间，取 roomId</li>
     *   <li>从缓存获取信令服务器密钥</li>
     *   <li>组装 {@link MqttMessageModel.WebRtcStartCommand} 并发布 MQTT</li>
     *   <li>阻塞等待机器人 ACK，超时或 success=false 时抛出异常</li>
     * </ol>
     *
     * @param masterDeviceCode 主控设备编码
     * @param robotDeviceCode   机器人设备编码
     * @throws RuntimeException 机器人未响应或返回失败时
     */
    private void sendWebRtcStartAndAwait(String masterDeviceCode, String robotDeviceCode) throws Exception {
        String webRtcCommandId = IdUtil.fastSimpleUUID();
        CompletableFuture<MqttMessageModel.WebRtcAck> future =
                webRtcAckPendingService.register(webRtcCommandId);

        try {
            // 获取房间（按主控编码查询或创建）
            MqttMessageModel.WebRtcStartCommand webRtcStartCommand = roomService.queryOrCreate(masterDeviceCode);
            webRtcStartCommand.setCommandId(webRtcCommandId);
            // 构建 TURN 服务器列表
            /*List<MqttMessageModel.WebRtcStartCommand.TurnServer> turnServers =
                    webRtcProperties.getTurnServers().stream()
                            .map(t -> MqttMessageModel.WebRtcStartCommand.TurnServer.builder()
                                    .url(t.getUrl())
                                    .username(t.getUsername())
                                    .password(t.getPassword())
                                    .build())
                            .toList();

            MqttMessageModel.WebRtcStartCommand startCmd = MqttMessageModel.WebRtcStartCommand.builder()
                    .commandId(webRtcCommandId)
                    .roomId(room.getRoomId())
                    .signalUrl(signalingKeyService.getServerUrl())
                    .signalKey(signalingKeyService.getCurrentKey())
                    .turnServers(turnServers)
                    .stunServers(webRtcProperties.getStunServerList())
                    .timestamp(System.currentTimeMillis())
                    .build();*/

            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_START, robotDeviceCode);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    objectMapper.writeValueAsString(webRtcStartCommand), MqttConstant.MQTT_QOS.QOS_1);
            log.info("[WebRtc] 已下发 start 指令 device={} commandId={} roomId={}",
                    robotDeviceCode, webRtcCommandId, webRtcStartCommand.getRoomId());
        } catch (Exception e) {
            webRtcAckPendingService.completeExceptionally(webRtcCommandId, e);
            throw new RuntimeException("WebRTC 开始指令发送失败: " + e.getMessage(), e);
        }

        // 等待机器人 ACK
        try {
            MqttMessageModel.WebRtcAck ack = future.get(WEBRTC_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ack.isSuccess()) {
                throw new RuntimeException("开启遥操失败：" +
                        (ack.getMessage() != null ? ack.getMessage() : "机器人拒绝 WebRTC 连接"));
            }
            log.info("[WebRtc] 收到 start ACK 成功 master={} commandId={}", masterDeviceCode, webRtcCommandId);
        } catch (TimeoutException e) {
            webRtcAckPendingService.completeExceptionally(webRtcCommandId, e);
            throw new RuntimeException("开启遥操失败：WebRTC 指令超时（" + WEBRTC_ACK_TIMEOUT_SECONDS + "s），机器人未响应");
        }
    }

    /**
     * 向机器人下发 WebRTC 停止指令（fire-and-forget，不等待 ACK）。
     *
     * @param robotDeviceCode 主控设备编码
     */
    private void sendWebRtcStop(String robotDeviceCode) {
        try {
            MqttMessageModel.WebRtcStopCommand stopCmd = MqttMessageModel.WebRtcStopCommand.builder()
                    .commandId(IdUtil.fastSimpleUUID())
                    .timestamp(System.currentTimeMillis())
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_STOP, robotDeviceCode);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    objectMapper.writeValueAsString(stopCmd), MqttConstant.MQTT_QOS.QOS_1);
            log.info("[WebRtc] 已下发 stop 指令 device={}", robotDeviceCode);
        } catch (Exception e) {
            // stop 是 fire-and-forget，失败只记录，不影响主流程
            log.warn("[WebRtc] stop 指令发送失败 device={}", robotDeviceCode, e);
        }
    }
}
