package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

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

    private final CommandEncryptService  encryptService;
    private final DeviceWebSocketServer  webSocketServer;
    private final StringRedisTemplate    redisTemplate;

    public void handle(String robotCode, String payload) {
        String masterCode = redisTemplate.opsForValue()
                .get(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode);
        if (masterCode == null) {
            log.warn("[MasterCommandHandler] 未找到机器人 {} 对应的主控缓存，忽略消息", robotCode);
            return;
        }
        String decrypted = encryptService.decryptFromDevice(masterCode, payload);
        log.info("[MasterCommandHandler] 解密成功, 机器人={} 主控={} 消息体={}", robotCode, masterCode, decrypted);
        webSocketServer.pushMasterCmdStatus(masterCode, decrypted);
    }

}

