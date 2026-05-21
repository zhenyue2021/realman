package org.jeecg.modules.device.mqtt.handler;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.mapper.ExtParamRecordIotMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executor;

/**
 * 处理设备请求外部系统服务参数（上行）
 *
 * <p>Topic 上行：device/{deviceCode}/ext-params/request
 * <p>Topic 下行：device/{deviceCode}/ext-params/ack
 *
 * <p>查询优先级：
 * <ol>
 *   <li>Redis 缓存（key: realman:ext:param:{req.targetSystem}:{req.sourceSystem}）</li>
 *   <li>降级查库（integration_external_param_record，取最新一条）并回写缓存</li>
 *   <li>库中亦无数据则响应 code=400</li>
 * </ol>
 *
 * <p>MQTT 回包在 {@code mqttPublishExecutor} 中异步执行，不占用路由线程。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtParamsRequestHandler {

    private static final String REDIS_KEY_PREFIX = "realman:ext:param:";
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private final CommandEncryptService encryptService;
    private final ObjectMapper objectMapper;
    private final MqttPublisher mqttPublisher;
    private final RedisUtil redisUtil;
    private final ExtParamRecordIotMapper extParamRecordIotMapper;

    @Autowired
    @Qualifier("mqttPublishExecutor")
    private Executor mqttPublishExecutor;

    public void handle(String deviceCode, String payload) throws Exception {
        String decrypted = encryptService.decryptFromDevice(deviceCode, payload);
        MqttMessageModel.ExtParamsRequest req =
                objectMapper.readValue(decrypted, MqttMessageModel.ExtParamsRequest.class);

        String sourceSystem = req.getSourceSystem();
        String targetSystem = req.getTargetSystem();

        log.info("[ExtParams] 设备请求外部参数: deviceCode={}, requestId={}, sourceSystem={}, targetSystem={}, bizType={}",
                deviceCode, req.getRequestId(), sourceSystem, targetSystem, req.getBizType());

        MqttMessageModel.ExtParamsResponse resp = buildResponse(req.getRequestId(), sourceSystem, targetSystem);
        String respTopic = String.format(DeviceConstant.MqttTopic.EXT_PARAMS_RESPONSE, deviceCode);
        String respJson = objectMapper.writeValueAsString(resp);

        mqttPublishExecutor.execute(() -> {
            try {
                mqttPublisher.publishToDevice(deviceCode, respTopic, respJson, MqttConstant.MQTT_QOS.QOS_1);
                log.debug("[ExtParams] 已异步下发 ack deviceCode={} requestId={}", deviceCode, req.getRequestId());
            } catch (Exception e) {
                log.error("[ExtParams] 异步下发 ack 失败 deviceCode={} requestId={}",
                        deviceCode, req.getRequestId(), e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private MqttMessageModel.ExtParamsResponse buildResponse(String requestId, String sourceSystem, String targetSystem) {
        String cacheKey = REDIS_KEY_PREFIX + targetSystem + ":" + sourceSystem;

        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            Map<String, Object> data = JSON.parseObject(cached.toString(), Map.class);
            log.info("[ExtParams] 缓存命中，响应设备: requestId={}, sourceSystem={}, targetSystem={}",
                    requestId, sourceSystem, targetSystem);
            return toResponse(requestId, sourceSystem, targetSystem, data);
        }

        log.warn("[ExtParams] Redis 缓存未命中，降级查库: sourceSystem={}, targetSystem={}", sourceSystem, targetSystem);
        Map<String, Object> dbData = extParamRecordIotMapper.findLatestData(targetSystem, sourceSystem);
        if (dbData == null || dbData.isEmpty()) {
            log.warn("[ExtParams] 库中亦无数据，返回 400: sourceSystem={}, targetSystem={}", sourceSystem, targetSystem);
            return MqttMessageModel.ExtParamsResponse.builder()
                    .requestId(requestId)
                    .sourceSystem(sourceSystem)
                    .targetSystem(targetSystem)
                    .bizType("upload_url_response")
                    .timestamp(LocalDateTime.now().toString())
                    .code(400)
                    .message("暂无可用的外部服务参数，请稍后重试")
                    .build();
        }

        long ttl = computeTtlSeconds(str(dbData, "utcExpiration"));
        redisUtil.set(cacheKey, JSON.toJSONString(dbData));
        redisUtil.expire(cacheKey, ttl);
        log.info("[ExtParams] 降级查库成功，已回写缓存: cacheKey={}, ttl={}s", cacheKey, ttl);

        return toResponse(requestId, sourceSystem, targetSystem, dbData);
    }

    private MqttMessageModel.ExtParamsResponse toResponse(String requestId, String reqSourceSystem, String targetSystem, Map<String, Object> data) {
        String localDateTime = LocalDateTime.now().toString();
        MqttMessageModel.ExtParamsResponse.StsCredential credential =
                MqttMessageModel.ExtParamsResponse.StsCredential.builder()
                        .endpoint(str(data, "endpoint"))
                        .bucket(str(data, "bucket"))
                        .bjExpiration(str(data, "bjExpiration"))
                        .utcExpiration(str(data, "utcExpiration"))
                        .accessKeyId(str(data, "accessKeyId"))
                        .accessKeySecret(str(data, "accessKeySecret"))
                        .securityToken(str(data, "securityToken"))
                        .build();
        MqttMessageModel.ExtParamsResponse.ResponseParams params =
                MqttMessageModel.ExtParamsResponse.ResponseParams.builder()
                        .timestamp(localDateTime)
                        .data(credential)
                        .build();
        MqttMessageModel.ExtParamsResponse resp = MqttMessageModel.ExtParamsResponse.builder()
                .requestId(requestId)
                .sourceSystem(reqSourceSystem)
                .targetSystem(targetSystem)
                .bizType("upload_url_response")
                .timestamp(localDateTime)
                .code(200)
                .message("success")
                .params(params)
                .build();
        log.info("[ExtParams] 参数已响应: requestId={}, accessKeyId={}", requestId, credential.getAccessKeyId());
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
