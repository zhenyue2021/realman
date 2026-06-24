package org.jeecg.modules.device.service.webrtc;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.service.signaling.SignalingKeyService;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 根据调度结果与配置组装 WebRTC 连接参数。
 */
@Component
@RequiredArgsConstructor
public class WebRtcEndpointAssembler {

    private final WebRtcProperties webRtcProperties;
    private final SignalingKeyService signalingKeyService;

    public MqttMessageModel.WebRtcCommand assemble(RoomTurnRouteCache route) {
        String serverIp = route.getServerIp();
        int serverPort = route.getServerPort();

        String turnUrl = String.format("turn:%s:%d?transport=udp", serverIp, serverPort);
        String stunUrl = String.format("stun:%s:%d", serverIp, serverPort);

        WebRtcProperties.TurnServer credentials = webRtcProperties.getTurnServer();
        MqttMessageModel.WebRtcCommand.TurnServer turnServer =
                MqttMessageModel.WebRtcCommand.TurnServer.builder()
                        .url(turnUrl)
                        .username(credentials.getUsername())
                        .password(credentials.getPassword())
                        .build();

        return MqttMessageModel.WebRtcCommand.builder()
                .signalUrl(signalingKeyService.buildSignalUrl(serverIp))
                .signalKey(route.getSignalKey())
                .turnServers(List.of(turnServer))
                .stunServers(List.of(stunUrl))
                .build();
    }
}
