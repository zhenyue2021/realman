package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SlamCommandPendingService extends MqttAckPendingService<MqttMessageModel.SlamAck> {

    public static final String CHANNEL_PREFIX = "iot:pending:slam-cmd:";

    public SlamCommandPendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "SlamCmdPending");
    }

    @Override
    protected MqttMessageModel.SlamAck deserialize(byte[] body) throws Exception {
        return objectMapper().readValue(body, MqttMessageModel.SlamAck.class);
    }
}
