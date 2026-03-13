package org.jeecg.modules.device.mqtt.handler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 机器人原始状态上报处理器（Topic: {robotCode}/slave/status）
 *
 * <p>该链路主要用于“主控遥操作机器人”场景：
 * <ul>
 *   <li>机器人通过 {robotCode}/slave/status 持续上报自身运行状态（明文 JSON）</li>
 *   <li>平台收到后不做复杂业务处理，仅简单转发到 WebSocket，供前端实时展示</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class RobotSlaveStatusHandler {

    private final DeviceWebSocketServer deviceWebSocketServer;

    /**
     * 处理机器人原始状态上报
     *
     * @param robotCode 机器人设备编码（来自 Topic 前缀）
     * @param payload   原始 JSON 文本（非加密）
     */
    public void handle(String robotCode, String payload) {
        log.info("[RobotSlaveStatusHandler] 收到机器人状态上报 robotCode={}, payload={}", robotCode, payload);
        // 直接通过 WebSocket 转发给订阅该机器人的前端（以及全局订阅者）
        deviceWebSocketServer.pushRobotStatus(robotCode, payload);
    }
}

