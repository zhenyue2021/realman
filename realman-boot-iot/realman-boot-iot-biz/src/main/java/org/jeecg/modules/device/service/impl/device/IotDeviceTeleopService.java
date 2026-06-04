package org.jeecg.modules.device.service.impl.device;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
import org.jeecg.modules.device.service.IIotDeviceRoomService;
import org.jeecg.modules.device.service.WebRtcAckPendingService;
import org.jeecg.modules.device.service.impl.master.TeleopRelationCacheService;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
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
    private final IIotDeviceCommandRecordService commandRecordService;
    private final StringRedisTemplate redisTemplate;
    private final TeleopRelationCacheService teleopRelationCacheService;
    private final IotDeviceCameraStreamService cameraStreamService;
    private final IIotDeviceRoomService roomService;
    private final WebRtcAckPendingService webRtcAckPendingService;

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
            String assignPayload = objectMapper.writeValueAsString(assignCmd);
            commandRecordService.recordSend(commandId, controller.getId(), controllerDeviceCode,
                    "teleop-assign", DeviceConstant.CommandDeviceType.MASTER, operator, assignPayload);
            mqttPublisher.publishToDevice(controllerDeviceCode, topic, assignPayload, MqttConstant.MQTT_QOS.QOS_1);

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
        try {
            /*            String commandId = IdUtil.fastSimpleUUID();
            long now = System.currentTimeMillis();
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
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);*/
            sendWebRtcStartAndAwait(controllerDeviceCode, robotDeviceCode, robotId, operator);
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
            robot = deviceSupport.requireByDeviceCode(robotCode);
        } else {
            throw new RuntimeException("deviceId 或 deviceCode 至少传一个");
        }
        if (robot == null || !Objects.equals(robot.getDeviceType(), DeviceConstant.DeviceTypeInteger.ROBOT)) {
            throw new RuntimeException("设备类型不匹配：不是机器人设备");
        }
        String robotDeviceCode = robot.getDeviceCode();

        long now = System.currentTimeMillis();
        try {
            String stopForControllerCommandId = IdUtil.fastSimpleUUID();
            String controllerTopic = String.format(DeviceConstant.MqttTopic.MASTER_STOP_CONTROL, controllerDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForController = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(stopForControllerCommandId)
                    .robotCode(controllerDeviceCode)
                    .workOrderId("STOP_MASTER")
                    .timestamp(now)
                    .build();
            String controllerStopPayload = objectMapper.writeValueAsString(stopForController);
            commandRecordService.recordSend(stopForControllerCommandId, controller.getId(), controllerDeviceCode,
                    "stop-control", DeviceConstant.CommandDeviceType.MASTER, operator, controllerStopPayload);
            mqttPublisher.publishToDevice(controllerDeviceCode, controllerTopic,
                    controllerStopPayload, MqttConstant.MQTT_QOS.QOS_1);

            String stopForRobotCommandId = IdUtil.fastSimpleUUID();
            String robotTopic = String.format(DeviceConstant.MqttTopic.DEVICE_STOP_CONTROL, robotDeviceCode);
            MqttMessageModel.RobotAssignCommand stopForRobot = MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(stopForRobotCommandId)
                    .robotCode(robotDeviceCode)
                    .workOrderId("STOP_SLAVE")
                    .timestamp(now)
                    .build();
            String stopForRobotPayload = objectMapper.writeValueAsString(stopForRobot);
            commandRecordService.recordSend(stopForRobotCommandId, robot.getId(), robotDeviceCode,
                    "stop-slave", DeviceConstant.CommandDeviceType.DEVICE, operator, stopForRobotPayload);
            mqttPublisher.publishToDevice(robotDeviceCode, robotTopic,
                    stopForRobotPayload, MqttConstant.MQTT_QOS.QOS_1);
            robot.setUseStatus(DeviceConstant.UseStatus.IDLE);
            deviceMapper.updateById(robot);

            logService.recordLog(controller.getId(), controllerDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：通知主控与机器人", "{commandId:" + stopForControllerCommandId + ",robotCode:" + robotDeviceCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotDeviceCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：设备使用状态置为空闲", "{commandId:" + stopForRobotCommandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);
//            teleopRelationCacheService.clearByMaster(controllerDeviceCode);
            sendWebRtcStop(robotDeviceCode, robot.getId(), operator);
            roomService.destroyRoom(controllerDeviceCode);
        } catch (Exception e) {
            throw new RuntimeException("停止遥操失败: " + e.getMessage(), e);
        }
    }

    /**
     * 通过设备编码停止遥操并销毁房间（同步等待 WebRTC stop ACK）。
     *
     * <p>与 {@link #stopTeleop} 的区别：
     * <ul>
     *   <li>入参为设备编码（而非数据库 ID），适合设备端/前端直接传码的场景</li>
     *   <li>WebRTC stop 指令等待机器人 ACK，确认 WebRTC 连接已断开后再销毁房间</li>
     * </ul>
     *
     * @throws RuntimeException 设备不存在、MQTT 发送失败、WebRTC stop 超时或失败时
     */
    public void stopTeleopByCode(String controllerCode, String robotCode, String operator) {
        // 1. 校验设备
        IotDevice controller = deviceSupport.requireByDeviceCode(controllerCode);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DeviceTypeInteger.CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：[" + controllerCode + "] 不是主控设备");
        }

        IotDevice robot = deviceSupport.requireByDeviceCode(robotCode);
        if (!Objects.equals(robot.getDeviceType(), DeviceConstant.DeviceTypeInteger.ROBOT)) {
            throw new RuntimeException("设备类型不匹配：[" + robotCode + "] 不是机器人设备");
        }

        long now = System.currentTimeMillis();

        try {
            String controllerTopicCommandId = IdUtil.fastSimpleUUID();
            // 2. 通知主控停止遥操
            String controllerTopic = String.format(DeviceConstant.MqttTopic.MASTER_STOP_CONTROL, controllerCode);
            String controllerStopPayload = objectMapper.writeValueAsString(
                    MqttMessageModel.RobotAssignCommand.builder()
                            .commandId(controllerTopicCommandId).robotCode(controllerCode)
                            .workOrderId("STOP_MASTER").timestamp(now).build());
            commandRecordService.recordSend(controllerTopicCommandId, controller.getId(), controllerCode,
                    "stop-control", DeviceConstant.CommandDeviceType.MASTER, operator, controllerStopPayload);
            mqttPublisher.publishToDevice(controllerCode, controllerTopic,
                    controllerStopPayload, MqttConstant.MQTT_QOS.QOS_1);

            // 3. 通知机器人停止遥操
            String robotTopicCommandId = IdUtil.fastSimpleUUID();
            String robotTopic = String.format(DeviceConstant.MqttTopic.DEVICE_STOP_CONTROL, robotCode);
            String stopSlavePayload = objectMapper.writeValueAsString(MqttMessageModel.RobotAssignCommand.builder()
                    .commandId(robotTopicCommandId).robotCode(robotCode)
                    .workOrderId("STOP_SLAVE").timestamp(now).build());
            commandRecordService.recordSend(robotTopicCommandId, robot.getId(), robotCode,
                    "stop-slave", DeviceConstant.CommandDeviceType.DEVICE, operator, stopSlavePayload);
            mqttPublisher.publishToDevice(robotCode, robotTopic,
                    stopSlavePayload,
                    MqttConstant.MQTT_QOS.QOS_1);
            // 4. 更新机器人使用状态
            robot.setUseStatus(DeviceConstant.UseStatus.IDLE);
            deviceMapper.updateById(robot);

            // 5. 记录日志
            logService.recordLog(controller.getId(), controllerCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：通知主控", "{commandId:" + controllerTopicCommandId + ",robotCode:" + robotCode + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            logService.recordLog(robot.getId(), robotCode,
                    DeviceConstant.OperationType.COMMAND_SEND,
                    "停止遥操：设备使用状态置为空闲", "{commandId:" + robotTopicCommandId + "}",
                    DeviceConstant.OperationSource.PLATFORM, "SUCCESS", null, operator, null);

            // 6. 清理遥操关系缓存
//            teleopRelationCacheService.clearByMaster(controllerCode);
//            log.info("[TeleopCache] 清除遥操关系缓存: master={} robot={}", controllerCode, robotCode);

            // 7. 下发 WebRTC stop 不等待 ACK
            sendWebRtcStopFireAndForget(robotCode, robot.getId(), operator);

            // 8. 销毁房间
            roomService.destroyRoom(controllerCode);

        } catch (RuntimeException e) {
            throw e;
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
     *   <li>组装 {@link MqttMessageModel.WebRtcCommand} 并发布 MQTT</li>
     *   <li>阻塞等待机器人 ACK，超时或 success=false 时抛出异常</li>
     * </ol>
     *
     * @param masterDeviceCode 主控设备编码
     * @param robotDeviceCode  机器人设备编码
     * @param robotId           机器人设备id
     * @throws RuntimeException 机器人未响应或返回失败时
     */
    private void sendWebRtcStartAndAwait(String masterDeviceCode, String robotDeviceCode,
                                          String robotId, String operator) throws Exception {
        String webRtcCommandId = IdUtil.fastSimpleUUID();
        CompletableFuture<MqttMessageModel.WebRtcAck> future =
                webRtcAckPendingService.register(webRtcCommandId);

        try {
            // 获取房间（按主控编码查询或创建），command 字段已由 queryOrCreate 设为 "start"
            MqttMessageModel.WebRtcCommand webRtcCommand = roomService.queryOrCreate(masterDeviceCode);
            webRtcCommand.setCommandId(webRtcCommandId);
            webRtcCommand.setCommand("start");

            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_REQUEST, robotDeviceCode);
            String startWebrtcPayload = objectMapper.writeValueAsString(webRtcCommand);
            commandRecordService.recordSend(webRtcCommandId, robotId, robotDeviceCode,
                    "start-webrtc", DeviceConstant.CommandDeviceType.DEVICE, operator, startWebrtcPayload);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    startWebrtcPayload, MqttConstant.MQTT_QOS.QOS_1);
            log.info("[WebRtc] 已下发 start 指令 device={} commandId={} roomId={}",
                    robotDeviceCode, webRtcCommandId, webRtcCommand.getRoomId());
            logService.recordLog(robotId, robotDeviceCode,
                    DeviceConstant.OperationType.WEBRTC,
                    "平台下发 WebRTC start 指令，等待机器人建立连接",
                    "{commandId:" + webRtcCommandId + ",topic:device/" + robotDeviceCode + "/webrtc/request}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, LocalDateTime.now());
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
     * @param robotDeviceCode   机器人编码
     * @param robotId           机器人id
     * @param operator          操作人
     */
    private void sendWebRtcStop(String robotDeviceCode, String robotId, String operator) {
        try {
            String commandId = IdUtil.fastSimpleUUID();
            MqttMessageModel.WebRtcCommand stopCmd = MqttMessageModel.WebRtcCommand.builder()
                    .command("stop")
                    .commandId(commandId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_REQUEST, robotDeviceCode);
            String stopCmdPayload = objectMapper.writeValueAsString(stopCmd);
            commandRecordService.recordSend(commandId, robotId, robotDeviceCode,
                    "stop-webrtc", DeviceConstant.CommandDeviceType.DEVICE, operator, stopCmdPayload);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    stopCmdPayload, MqttConstant.MQTT_QOS.QOS_1);
            logService.recordLog(robotId, robotDeviceCode,
                    DeviceConstant.OperationType.WEBRTC,
                    "平台下发 WebRTC stop 指令",
                    "{commandId:" + commandId + ",topic:" + topic + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            log.info("[WebRtc] 已下发 stop 指令 device={}", robotDeviceCode);
        } catch (Exception e) {
            // stop 是 fire-and-forget，失败只记录，不影响主流程
            log.warn("[WebRtc] stop 指令发送失败 device={}", robotDeviceCode, e);
        }
    }

    /**
     * 向机器人下发 WebRTC 停止指令，不等待 ACK（fire-and-forget）。
     * 适用于已无法等待响应的兜底清理场景（如超时回滚）。
     *
     * @param robotDeviceCode   机器人设备编码
     * @param robotId           机器人设备Id
     * @param operator          操作人
     */
    private void sendWebRtcStopFireAndForget(String robotDeviceCode, String robotId, String operator) {
        try {
            String commandId = IdUtil.fastSimpleUUID();
            MqttMessageModel.WebRtcCommand stopCmd = MqttMessageModel.WebRtcCommand.builder()
                    .command("stop")
                    .commandId(commandId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_REQUEST, robotDeviceCode);
            String stopCmdPayload = objectMapper.writeValueAsString(stopCmd);
            commandRecordService.recordSend(commandId, robotId, robotDeviceCode,
                    "stop-webrtc", DeviceConstant.CommandDeviceType.DEVICE, operator, stopCmdPayload);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    stopCmdPayload, MqttConstant.MQTT_QOS.QOS_1);
            logService.recordLog(robotId, robotDeviceCode,
                    DeviceConstant.OperationType.WEBRTC,
                    "平台下发 WebRTC stop 指令（不等待 ACK）",
                    "{commandId:" + commandId + ",topic:" + topic + "}",
                    DeviceConstant.OperationSource.PLATFORM, "PENDING", null, operator, null);
            log.info("[WebRtc] 已下发 stop 指令（不等待ACK）device={}", robotDeviceCode);
        } catch (Exception e) {
            log.warn("[WebRtc] fire-and-forget stop 指令发送失败 device={}", robotDeviceCode, e);
        }
    }

    /**
     * 向机器人下发 WebRTC 停止指令，并同步等待 ACK（最多 {@value #WEBRTC_ACK_TIMEOUT_SECONDS} 秒）。
     *
     * @param robotDeviceCode 机器人设备编码
     * @throws RuntimeException 超时或机器人返回失败时
     */
    private void sendWebRtcStopAndAwait(String robotDeviceCode, String robotId, String operator) throws Exception {
        String commandId = IdUtil.fastSimpleUUID();
        CompletableFuture<MqttMessageModel.WebRtcAck> future =
                webRtcAckPendingService.register(commandId);
        try {
            MqttMessageModel.WebRtcCommand stopCmd = MqttMessageModel.WebRtcCommand.builder()
                    .command("stop")
                    .commandId(commandId)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String topic = String.format(DeviceConstant.MqttTopic.WEBRTC_REQUEST, robotDeviceCode);
            String stopPayload = objectMapper.writeValueAsString(stopCmd);
            commandRecordService.recordSend(commandId, robotId, robotDeviceCode,
                    "stop-webrtc", DeviceConstant.CommandDeviceType.DEVICE, operator, stopPayload);
            mqttPublisher.publishToDevice(robotDeviceCode, topic,
                    stopPayload, MqttConstant.MQTT_QOS.QOS_1);
            log.info("[WebRtc] 已下发 stop 指令（等待ACK）device={} commandId={}", robotDeviceCode, commandId);
        } catch (Exception e) {
            webRtcAckPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("WebRTC 停止指令发送失败: " + e.getMessage(), e);
        }

        try {
            MqttMessageModel.WebRtcAck ack = future.get(WEBRTC_ACK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!ack.isSuccess()) {
                throw new RuntimeException("停止遥操失败：" +
                        (ack.getMessage() != null ? ack.getMessage() : "机器人拒绝 WebRTC 停止"));
            }
            log.info("[WebRtc] 收到 stop ACK 成功 device={} commandId={}", robotDeviceCode, commandId);
        } catch (TimeoutException e) {
            webRtcAckPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("停止遥操失败：WebRTC stop 指令超时（" + WEBRTC_ACK_TIMEOUT_SECONDS + "s），机器人未响应");
        }
    }
}
