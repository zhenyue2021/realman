package org.jeecg.modules.device.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DeviceCameraStreamPendingService extends MqttAckPendingService<List<MqttMessageModel.CameraInfo>> {

    public static final String CHANNEL_PREFIX = "iot:pending:camera:";

    public DeviceCameraStreamPendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "CameraStreamPending");
    }

    @Override
    protected List<MqttMessageModel.CameraInfo> deserialize(byte[] body) throws Exception {
        return readValue(objectMapper(), body, new TypeReference<>() {});
    }
}
