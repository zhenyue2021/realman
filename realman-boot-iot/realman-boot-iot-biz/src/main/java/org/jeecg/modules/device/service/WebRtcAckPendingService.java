package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class WebRtcAckPendingService extends RedisClusterPendingService<MqttMessageModel.WebRtcAck> {

    public static final String CHANNEL_PREFIX = "iot:pending:webrtc:";

    public WebRtcAckPendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "WebRtcAckPending");
    }

    @Override
    protected MqttMessageModel.WebRtcAck deserialize(byte[] body) throws Exception {
        return objectMapper().readValue(body, MqttMessageModel.WebRtcAck.class);
    }
}
