package org.jeecg.modules.device.darwin.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.darwin.constant.DarwinTopicConstant;
import org.jeecg.modules.device.darwin.dto.DarwinOssAuthRequestDTO;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class DarwinUploadTokenService {

    private static final long TOKEN_TTL_HOURS = 1;

    // 原子获取并删除：GETDEL（Redis 6.2+ 原生；旧版走 Lua 等价）
    private static final DefaultRedisScript<String> GET_AND_DEL_SCRIPT = new DefaultRedisScript<>(
            "local v = redis.call('GET', KEYS[1]); if v then redis.call('DEL', KEYS[1]) end; return v",
            String.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    /** 生成上传 Token，将请求元数据序列化存入 Redis */
    public String generateToken(DarwinOssAuthRequestDTO request) {
        String token = UUID.randomUUID().toString().replace("-", "");
        String key = DarwinTopicConstant.REDIS_UPLOAD_TOKEN + token;
        try {
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(request),
                    TOKEN_TTL_HOURS, TimeUnit.HOURS);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("序列化上传 Token 失败", e);
        }
        return token;
    }

    /** Token 过期时间（调用方用于填充响应） */
    public LocalDateTime tokenExpireAt() {
        return LocalDateTime.now().plusHours(TOKEN_TTL_HOURS);
    }

    /**
     * 原子校验并消费 Token（一次性）。
     * @return Token 对应的请求元数据；Token 不存在或已过期返回 null
     */
    public DarwinOssAuthRequestDTO validateAndConsume(String token) {
        if (token == null || token.isBlank()) return null;
        String key = DarwinTopicConstant.REDIS_UPLOAD_TOKEN + token;
        String value = redisTemplate.execute(GET_AND_DEL_SCRIPT, List.of(key));
        if (value == null) return null;
        try {
            return objectMapper.readValue(value, DarwinOssAuthRequestDTO.class);
        } catch (Exception e) {
            log.error("[Darwin] 反序列化上传 Token 元数据失败 token={}", token, e);
            return null;
        }
    }
}
