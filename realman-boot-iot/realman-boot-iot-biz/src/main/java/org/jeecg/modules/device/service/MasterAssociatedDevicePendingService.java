package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class MasterAssociatedDevicePendingService
        extends RedisClusterPendingService<MqttMessageModel.AssociatedDeviceResponse> {

    public static final String CHANNEL_PREFIX = "iot:pending:associated-device:";

    public MasterAssociatedDevicePendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "AssociatedDevicePending");
    }

    @Override
    protected MqttMessageModel.AssociatedDeviceResponse deserialize(byte[] body) throws Exception {
        return objectMapper().readValue(body, MqttMessageModel.AssociatedDeviceResponse.class);
    }
}
