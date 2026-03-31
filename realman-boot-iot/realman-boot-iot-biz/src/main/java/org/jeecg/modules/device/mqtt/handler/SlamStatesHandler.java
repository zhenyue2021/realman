package org.jeecg.modules.device.mqtt.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IIotSlamCommandService;
import org.springframework.stereotype.Component;

/**
 * 处理设备上报建图/定位/导航状态（上行）
 *
 * <p>Topic：device/{deviceCode}/slam/states
 *
 * <p>状态数据仅写入 Redis 缓存（TTL=5min），不落库，供前端实时查询当前设备 SLAM 模式及位姿。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SlamStatesHandler {

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final IIotSlamCommandService slamCommandService;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.SlamStates states = objectMapper.readValue(decrypted, MqttMessageModel.SlamStates.class);
        log.debug("[SlamStates] 收到状态上报: deviceCode={}, mode={}", deviceCode, states.getSlamNavMode());
        slamCommandService.handleStates(deviceCode, states);
    }
}
