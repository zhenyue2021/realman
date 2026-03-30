package org.jeecg.modules.device.mqtt.handler;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mapper.ExtParamRecordIotMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * 处理设备请求外部系统服务参数（上行）
 *
 * <p>Topic 上行：device/{deviceCode}/ext-params/request
 * <p>Topic 下行：device/{deviceCode}/ext-params/response
 *
 * <p>查询优先级：
 * <ol>
 *   <li>Redis 缓存（key: realman:ext:param:{sourceSystem}）</li>
 *   <li>降级查库（integration_external_param_record，取最新一条）并回写缓存</li>
 *   <li>库中亦无数据则响应 code=404</li>
 * </ol>
 *
 * <p>所有设备共享同一套参数，每次外部系统推送（/api/integration/external/receiveParams）
 * 都会刷新 Redis 缓存，TTL 与凭证过期时间对齐。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtParamsRequestHandler {

    /** 与 ExternalParamRecordServiceImpl.REDIS_KEY_PREFIX 保持一致 */
    private static final String REDIS_KEY_PREFIX = "realman:ext:param:";

    /** utcExpiration 解析失败时的兜底 TTL（1 小时） */
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    /**
     * 设备请求中未携带 sourceSystem 时使用的默认值，
     * 可通过配置项 integration.ext-params.default-source-system 覆盖。
     */
    @Value("${integration.ext-params.default-source-system:DEW}")
    private String defaultSourceSystem;

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final MqttPublisher mqttPublisher;
    private final RedisUtil redisUtil;
    private final ExtParamRecordIotMapper extParamRecordIotMapper;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.ExtParamsRequest req =
                objectMapper.readValue(decrypted, MqttMessageModel.ExtParamsRequest.class);

        // 确定 sourceSystem：优先使用设备请求中的值，缺省则用配置默认值
        String sourceSystem = (req.getSourceSystem() != null && !req.getSourceSystem().isBlank())
                ? req.getSourceSystem()
                : defaultSourceSystem;

        log.info("[ExtParams] 设备请求外部参数: deviceCode={}, commandId={}, sourceSystem={}",
                deviceCode, req.getCommandId(), sourceSystem);

        MqttMessageModel.ExtParamsResponse resp = buildResponse(req.getCommandId(), sourceSystem);

        String respTopic = String.format(DeviceConstant.MqttTopic.EXT_PARAMS_RESPONSE, deviceCode);
        mqttPublisher.publishToDevice(deviceCode, respTopic, objectMapper.writeValueAsString(resp), 1);
    }

    @SuppressWarnings("unchecked")
    private MqttMessageModel.ExtParamsResponse buildResponse(String commandId, String sourceSystem) {
        // 1. 优先读 Redis 缓存
        Object cached = redisUtil.get(REDIS_KEY_PREFIX + sourceSystem);
        if (cached != null) {
            Map<String, Object> data = JSON.parseObject(cached.toString(), Map.class);
            log.info("[ExtParams] 缓存命中，响应设备: commandId={}, sourceSystem={}", commandId, sourceSystem);
            return toResponse(commandId, data);
        }

        // 2. 缓存未命中，降级查库
        log.warn("[ExtParams] Redis 缓存未命中，降级查库: sourceSystem={}", sourceSystem);
        Map<String, Object> dbData = extParamRecordIotMapper.findLatestDataBySourceSystem(sourceSystem);
        if (dbData == null || dbData.isEmpty()) {
            log.warn("[ExtParams] 库中亦无数据，返回 400: sourceSystem={}", sourceSystem);
            return MqttMessageModel.ExtParamsResponse.builder()
                    .commandId(commandId)
                    .code(400)
                    .message("暂无可用的外部服务参数，请稍后重试")
                    .build();
        }

        // 3. 查库成功，回写 Redis 缓存（TTL 跟随凭证过期时间）
        String cacheKey = REDIS_KEY_PREFIX + sourceSystem;
        long ttl = computeTtlSeconds(str(dbData, "utcExpiration"));
        redisUtil.set(cacheKey, JSON.toJSONString(dbData));
        redisUtil.expire(cacheKey, ttl);
        log.info("[ExtParams] 降级查库成功，已回写缓存: sourceSystem={}, ttl={}s", sourceSystem, ttl);

        return toResponse(commandId, dbData);
    }

    private MqttMessageModel.ExtParamsResponse toResponse(String commandId, Map<String, Object> data) {
        MqttMessageModel.ExtParamsResponse resp = MqttMessageModel.ExtParamsResponse.builder()
                .commandId(commandId)
                .code(0)
                .endpoint(str(data, "endpoint"))
                .bucket(str(data, "bucket"))
                .bjExpiration(str(data, "bjExpiration"))
                .utcExpiration(str(data, "utcExpiration"))
                .accessKeyId(str(data, "accessKeyId"))
                .accessKeySecret(str(data, "accessKeySecret"))
                .securityToken(str(data, "securityToken"))
                .build();
        log.info("[ExtParams] 参数已响应: commandId={}, accessKeyId={}", commandId, resp.getAccessKeyId());
        return resp;
    }

    private long computeTtlSeconds(String utcExpiration) {
        if (utcExpiration == null || utcExpiration.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            long remaining = Instant.parse(utcExpiration).getEpochSecond() - Instant.now().getEpochSecond();
            return remaining > 0 ? remaining : DEFAULT_TTL_SECONDS;
        } catch (Exception e) {
            log.warn("[ExtParams] utcExpiration 解析失败，使用默认 TTL: {}", utcExpiration);
            return DEFAULT_TTL_SECONDS;
        }
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }
}
