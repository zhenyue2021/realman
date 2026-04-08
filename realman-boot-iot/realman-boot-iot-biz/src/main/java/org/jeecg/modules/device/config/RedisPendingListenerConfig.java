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
 * 跨节点 PendingService 的 Redis Pub/Sub 监听注册
 *
 * <p>每个 PendingService 实现了 {@link org.springframework.data.redis.connection.MessageListener}，
 * 此处将它们绑定到各自的 Redis 频道前缀（PatternTopic）。
 *
 * <p>工作原理：
 * <pre>
 *   Node A  sendCommand() → register(commandId) → Future 存入本地 map → 发 MQTT → future.get(30s) 阻塞
 *   Node B  处理 MQTT ack  → handleAck() → 更新 DB → pendingService.complete(commandId, ack)
 *                                                      → redisTemplate.convertAndSend(channel, json)
 *   Node A  onMessage() 收到 Redis 消息 → 从本地 map 找到 Future → future.complete(ack) → 解锁
 * </pre>
 *
 * <p>配合 EMQX {@code $share/iot-cluster/} 共享订阅使用：
 * <ul>
 *   <li>MQTT 消息只有一个节点处理，消除重复 DB 写入</li>
 *   <li>Redis Pub/Sub 保证结果广播到持有 Future 的节点</li>
 * </ul>
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
