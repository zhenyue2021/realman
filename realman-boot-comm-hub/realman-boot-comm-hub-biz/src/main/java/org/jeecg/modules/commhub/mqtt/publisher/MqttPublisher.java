package org.jeecg.modules.commhub.mqtt.publisher;

import cn.hutool.core.util.IdUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.mqttv5.client.MqttClient;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.commhub.contract.constant.CommHubTopicConstants;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.commhub.mqtt.MqttAckPendingService;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 统一下行发布：{@code CommHubFeignClient.publish}（内部调用）与
 * {@code POST /api/v1/devices/{id}/mqtt-bridge/publish}（HTTP-MQTT 桥接）
 * 共用同一套实现，见设备通信中台详细设计 4.3.1。
 *
 * <p>已知限制：{@code request.encrypt=true} 时暂不做真正的按设备 AES 加密——本轮
 * 未引入设备级密钥分发/协商机制，贸然自造一套加密方案风险大于收益；先按明文发布
 * （生产环境应确保 MQTT 连接层走 TLS），待有明确的设备侧密钥管理方案后再补上应用层
 * 加密。这与"不做半成品实现"的原则不冲突：该参数被诚实地忽略并记录警告，而不是
 * 假装加密生效。
 */
@Slf4j
@Component
public class MqttPublisher {

    private static final String CLAIM_COMMAND_ID = "commandId";
    private static final long DEFAULT_ACK_TIMEOUT_MS = 10_000L;

    private final MqttClient publishClient;
    private final MqttAckPendingService ackPendingService;
    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public MqttPublisher(@Qualifier("mqttPublishClient") MqttClient publishClient,
                          MqttAckPendingService ackPendingService,
                          DeviceInfoFeignClient deviceInfoFeignClient) {
        this.publishClient = publishClient;
        this.ackPendingService = ackPendingService;
        this.deviceInfoFeignClient = deviceInfoFeignClient;
    }

    public MqttPublishResult publish(MqttPublishRequest request) {
        String deviceCode = resolveDeviceCode(request.getDeviceId());
        if (deviceCode == null) {
            throw new JeecgBootBizTipException("ERR_DEVICE_NOT_FOUND");
        }
        if (request.isEncrypt()) {
            log.warn("[comm-hub] encrypt=true 但本服务尚未接入设备级加密方案，按明文发布 deviceCode={}", deviceCode);
        }

        String topic = CommHubTopicConstants.fullTopic(deviceCode, request.getTopicSuffix());
        String commandId = IdUtil.fastSimpleUUID();
        String bodyJson = buildPayloadJson(request.getPayload(), commandId);

        MqttPublishResult result = new MqttPublishResult();
        if (!request.isWaitAck()) {
            publishRaw(topic, bodyJson, request.getQos());
            result.setStatus("PUBLISHED");
            return result;
        }

        long timeoutMs = request.getAckTimeoutMs() != null ? request.getAckTimeoutMs() : DEFAULT_ACK_TIMEOUT_MS;
        String ackTopicSuffix = defaultIfBlank(request.getAckTopicSuffix(), CommHubTopicConstants.TOPIC_BRIDGE_ACK);
        String ackCommandIdField = defaultIfBlank(request.getAckCommandIdField(), CLAIM_COMMAND_ID);
        CompletableFuture<String> future = ackPendingService.register(commandId, ackTopicSuffix, ackCommandIdField, timeoutMs);
        log.debug("[comm-hub] register pending ACK commandId={} ackTopicSuffix={} ackCommandIdField={}",
                commandId, ackTopicSuffix, ackCommandIdField);
        try {
            publishRaw(topic, bodyJson, request.getQos());
        } catch (RuntimeException e) {
            ackPendingService.abandon(commandId);
            throw e;
        }
        try {
            String ackJson = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            result.setStatus("ACKED");
            result.setAckPayload(parseAckPayload(ackJson));
        } catch (TimeoutException e) {
            ackPendingService.abandon(commandId);
            result.setStatus("TIMEOUT");
        } catch (Exception e) {
            ackPendingService.abandon(commandId);
            log.warn("[comm-hub] publish-and-wait 等待异常 commandId={}: {}", commandId, e.getMessage());
            result.setStatus("TIMEOUT");
        }
        return result;
    }

    private void publishRaw(String topic, String payload, Integer qos) {
        try {
            MqttMessage message = new MqttMessage(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            message.setQos(qos != null ? qos : 1);
            message.setRetained(false);
            publishClient.publish(topic, message);
        } catch (MqttException e) {
            throw new JeecgBootBizTipException("MQTT 发布失败：" + e.getMessage());
        }
    }

    private String buildPayloadJson(Object payload, String commandId) {
        try {
            ObjectNode node;
            if (payload == null) {
                node = objectMapper.createObjectNode();
            } else {
                com.fasterxml.jackson.databind.JsonNode tree = objectMapper.valueToTree(payload);
                if (tree.isObject()) {
                    node = (ObjectNode) tree;
                } else {
                    // payload 不是 JSON 对象（如裸字符串/数组），套一层 data 字段承载，避免 commandId 与其冲突
                    node = objectMapper.createObjectNode();
                    node.set("data", tree);
                }
            }
            node.put(CLAIM_COMMAND_ID, commandId);
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            log.warn("[comm-hub] 下行载荷序列化失败，退化为仅 commandId: {}", e.getMessage());
            return "{\"" + CLAIM_COMMAND_ID + "\":\"" + commandId + "\"}";
        }
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private Map<String, Object> parseAckPayload(String ackJson) {
        try {
            return objectMapper.readValue(ackJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[comm-hub] ACK 载荷解析失败: {}", e.getMessage());
            return Map.of();
        }
    }

    private String resolveDeviceCode(String deviceIdOrCode) {
        try {
            Result<DeviceInfoDTO> byCode = deviceInfoFeignClient.getDeviceByCode(deviceIdOrCode);
            if (byCode != null && byCode.isSuccess() && byCode.getResult() != null) {
                return byCode.getResult().getDeviceCode();
            }
        } catch (Exception ignored) {
            // 按 deviceCode 查询未命中，继续尝试按内部 deviceId 查询
        }
        try {
            Result<DeviceInfoDTO> byId = deviceInfoFeignClient.getDevice(deviceIdOrCode);
            if (byId != null && byId.isSuccess() && byId.getResult() != null) {
                return byId.getResult().getDeviceCode();
            }
        } catch (Exception e) {
            log.debug("[comm-hub] 设备信息基础服务查询未命中或不可用 deviceIdOrCode={}: {}", deviceIdOrCode, e.getMessage());
        }
        return null;
    }
}
