package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.vo.SportSpeedVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class SportSpeedQueryPendingService extends MqttAckPendingService<SportSpeedVO> {

    public static final String CHANNEL_PREFIX = "iot:pending:sport-speed:";

    public SportSpeedQueryPendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "SportSpeedPending");
    }

    @Override
    protected SportSpeedVO deserialize(byte[] body) throws Exception {
        return objectMapper().readValue(body, SportSpeedVO.class);
    }
}
