package org.jeecg.modules.device.config;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.jeecg.modules.device.service.ForceFeedbackQueryPendingService;
import org.jeecg.modules.device.service.MasterAssociatedDevicePendingService;
import org.jeecg.modules.device.service.NavigationPathMonitorService;
import org.jeecg.modules.device.service.SlamCommandPendingService;
import org.jeecg.modules.device.service.WebRtcAckPendingService;
import org.jeecg.modules.device.service.SportSpeedQueryPendingService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 跨节点 MQTT 协调服务的 Redis Pub/Sub 监听注册
 *
 * <p>{@link MqttAckPendingService}：单次 MQTT ACK → {@link java.util.concurrent.CompletableFuture}；
 * {@link ClusterScheduledMonitor}：长周期任务停止广播。
 *
 * <p>工作原理（ACK 等待）：
 * <pre>
 *   Node A  sendCommand() → register(commandId) → Future 阻塞 → 发 MQTT
 *   Node B  处理 MQTT ack → complete(commandId, ack) → Redis publish
 *   Node A  onMessage() → future.complete(ack)
 * </pre>
 */
@Configuration
@RequiredArgsConstructor
public class RedisPendingListenerConfig {

    private final RedisConnectionFactory redisConnectionFactory;

    private final DeviceCameraStreamPendingService  cameraStreamPendingService;
    private final ForceFeedbackQueryPendingService  forceFeedbackPendingService;
    private final SportSpeedQueryPendingService     sportSpeedPendingService;
    private final MasterAssociatedDevicePendingService associatedDevicePendingService;
    private final SlamCommandPendingService         slamCommandPendingService;
    private final WebRtcAckPendingService           webRtcAckPendingService;
    private final NavigationPathMonitorService      navigationPathMonitorService;
    private final DeviceWebSocketServer             deviceWebSocketServer;

    @Bean
    public RedisMessageListenerContainer pendingListenerContainer() {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);

        container.addMessageListener(cameraStreamPendingService,
                new PatternTopic(DeviceCameraStreamPendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(forceFeedbackPendingService,
                new PatternTopic(ForceFeedbackQueryPendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(sportSpeedPendingService,
                new PatternTopic(SportSpeedQueryPendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(associatedDevicePendingService,
                new PatternTopic(MasterAssociatedDevicePendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(slamCommandPendingService,
                new PatternTopic(SlamCommandPendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(webRtcAckPendingService,
                new PatternTopic(WebRtcAckPendingService.CHANNEL_PREFIX + "*"));

        container.addMessageListener(navigationPathMonitorService,
                new PatternTopic(NavigationPathMonitorService.STOP_CHANNEL_PREFIX + "*"));

        container.addMessageListener(deviceWebSocketServer,
                new PatternTopic(DeviceWebSocketServer.WS_PUSH_CHANNEL_PREFIX + "*"));

        return container;
    }
}
