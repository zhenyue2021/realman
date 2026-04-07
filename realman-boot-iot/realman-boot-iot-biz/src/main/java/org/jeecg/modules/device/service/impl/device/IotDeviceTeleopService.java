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
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 遥操作编排：设备校验、MQTT 指令下发、DB 状态更新、Redis 缓存清理。
 * 事务由调用方 {@link org.jeecg.modules.device.service.impl.IotDeviceServiceImpl} 统一控制。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IotDeviceTeleopService {

    private final IotDeviceSupport deviceSupport;
    private final IotDeviceMapper deviceMapper;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;
    private final IDeviceOperationLogService logService;
    private final StringRedisTemplate redisTemplate;
    private final IotDeviceCameraStreamService cameraStreamService;

    public List<DeviceCameraStreamVO> startTeleop(String controllerId, String robotId, String operator) {
        IotDevice controller = deviceSupport.require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DEVICE_TYPE_INTEGER.CONTROLLER)) {
            throw new RuntimeException("设备类型不匹配：不是主控设备");
        }
        IotDevice robot = deviceSupport.require(robotId);
        if (!Objects.equals(robot.getDeviceType(), DeviceConstant.DEVICE_TYPE_INTEGER.ROBOT)) {
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

    public void stopTeleop(String controllerId, String robotId, String robotCode, String operator) {
        IotDevice controller = deviceSupport.require(controllerId);
        if (!Objects.equals(controller.getDeviceType(), DeviceConstant.DEVICE_TYPE_INTEGER.CONTROLLER)) {
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
        if (robot == null || !Objects.equals(robot.getDeviceType(), DeviceConstant.DEVICE_TYPE_INTEGER.ROBOT)) {
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
        } catch (Exception e) {
            throw new RuntimeException("停止遥操失败: " + e.getMessage(), e);
        }
    }
}
