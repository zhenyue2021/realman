package org.jeecg.modules.device.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.vo.ForceFeedbackVO;
import org.jeecg.modules.device.vo.SportSpeedVO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class ForceFeedbackQueryPendingService extends RedisClusterPendingService<ForceFeedbackVO> {

    public static final String CHANNEL_PREFIX = "iot:pending:force-feedback:";

    public ForceFeedbackQueryPendingService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
        super(redisTemplate, objectMapper, CHANNEL_PREFIX, "ForceFeedbackPending");
    }

    @Override
    protected ForceFeedbackVO deserialize(byte[] body) throws Exception {
        return objectMapper().readValue(body, ForceFeedbackVO.class);
    }
}
