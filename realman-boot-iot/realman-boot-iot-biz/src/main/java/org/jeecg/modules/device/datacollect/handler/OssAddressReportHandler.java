package org.jeecg.modules.device.datacollect.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mqtt.OssAddressReportMsg;
import org.jeecg.modules.device.datacollect.producer.FileAddressReportProducer;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * MQTT 上行处理：机器人 OSS 上传完成后回传文件地址（device/{code}/datacollect/ossAdressReport）。
 *
 * <p>处理流程：
 * <ol>
 *   <li>解析消息体，校验 oss.list 非空</li>
 *   <li>Redis 去重（deviceCode + ossAddress，TTL 24h），防机器人重复上报</li>
 *   <li>调用 {@link FileAddressReportProducer#send} 将地址转发给数采平台</li>
 * </ol>
 *
 * <p>工单关联（workOrderId / taskId）将在工单集成完成后补充，当前传 null。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class OssAddressReportHandler {

    private final FileAddressReportProducer fileAddressReportProducer;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void handle(String deviceCode, String payload) {
        OssAddressReportMsg msg;
        try {
            msg = objectMapper.readValue(payload, OssAddressReportMsg.class);
        } catch (Exception e) {
            log.error("[DataCollect] ossAdressReport 反序列化失败 deviceCode={} payload={}",
                    deviceCode, payload, e);
            return;
        }

        OssAddressReportMsg.OssInfo oss = msg.getOss();
        if (oss == null || oss.getList() == null || oss.getList().isEmpty()) {
            log.warn("[DataCollect] ossAdressReport 文件列表为空 deviceCode={}", deviceCode);
            return;
        }

        // 去重：deviceCode + ossAddress 组合，防机器人因网络重发导致重复上报
        String dedupKey = DataCollectConstant.REDIS_REPORT_DEDUP_PREFIX
                + deviceCode + ":" + oss.getAddress();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.info("[DataCollect] ossAdressReport 重复上报，跳过 deviceCode={} address={}",
                    deviceCode, oss.getAddress());
            return;
        }

        fileAddressReportProducer.send(
                deviceCode,
                null,              // workOrderId：工单集成后补充
                null,              // taskId：工单集成后补充
                oss.getAddress(),
                oss.getList(),
                MDC.get("traceId")
        );
        log.info("[DataCollect] OSS地址已上报 deviceCode={} fileCount={}", deviceCode, oss.getList().size());
    }
}
