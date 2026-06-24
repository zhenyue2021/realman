package org.jeecg.modules.device.service.webrtc;

import org.jeecg.modules.device.config.WebRtcProperties;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.service.signaling.SignalingKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;

class WebRtcEndpointAssemblerTest {

    @Test
    void assembleTurnStunAndSignalUrl() {
        WebRtcProperties properties = new WebRtcProperties();
        WebRtcProperties.TurnServer turnServer = new WebRtcProperties.TurnServer();
        turnServer.setUsername("realman");
        turnServer.setPassword("secret");
        properties.setTurnServer(turnServer);
        WebRtcProperties.Signaling.Server server = new WebRtcProperties.Signaling.Server();
        server.setPort(8091);
        WebRtcProperties.Signaling signaling = new WebRtcProperties.Signaling();
        signaling.setServer(server);
        properties.setSignaling(signaling);

        SignalingKeyService signalingKeyService = Mockito.mock(SignalingKeyService.class);
        Mockito.when(signalingKeyService.buildSignalUrl("47.102.207.121"))
                .thenReturn("ws://47.102.207.121:8091");

        WebRtcEndpointAssembler assembler = new WebRtcEndpointAssembler(properties, signalingKeyService);
        MqttMessageModel.WebRtcCommand cmd = assembler.assemble(
                new RoomTurnRouteCache("47.102.207.121", 3479, "session-key"));

        assertEquals("ws://47.102.207.121:8091", cmd.getSignalUrl());
        assertEquals("session-key", cmd.getSignalKey());
        assertEquals(1, cmd.getTurnServers().size());
        assertEquals("turn:47.102.207.121:3479?transport=udp", cmd.getTurnServers().get(0).getUrl());
        assertEquals("realman", cmd.getTurnServers().get(0).getUsername());
        assertEquals("secret", cmd.getTurnServers().get(0).getPassword());
        assertIterableEquals(
                java.util.List.of("stun:47.102.207.121:3479"),
                cmd.getStunServers());
    }
}
