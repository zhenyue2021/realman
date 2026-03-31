package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.mapper.IotDeviceConfigMapper;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.ForceFeedbackQueryPendingService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.SportSpeedQueryPendingService;
import org.jeecg.modules.device.vo.ForceFeedbackVO;
import org.jeecg.modules.device.vo.SportSpeedVO;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 主控设备指令 ACK 处理器（Topic: {controllerCode}/master/cmd/）
 *
 * <p>与 {@link DeviceCommandAckHandler} 类似，但语义上区分“主控设备”与普通设备，
 * 便于后续做独立的操作类型或审计策略。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MasterCommandHandler {

    private final CommandEncryptService      encryptService;
    private final DeviceWebSocketServer          webSocketServer;

    public void handle(String deviceCode, String payload) {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        log.info("[MasterCommandHandler] 解密成功, 主控上报消息体为: {}", decrypted);
        webSocketServer.pushMasterCmdStatus(deviceCode, decrypted);
    }

}

