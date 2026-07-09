package org.jeecg.modules.ota.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.service.IOtaDownlinkService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * 下行任务通知，经设备通信中台统一下行发布，见 OTA 平台详细设计第二章协议映射表。
 * 全部 fire-and-forget（{@code waitAck=false}）——设备是否收到、是否开始执行由
 * 设备自己经 {@code ota/progress} 上行报告，不依赖 MQTT 层的 publish-and-wait 确认。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtaDownlinkServiceImpl implements IOtaDownlinkService {

    private static final String TOPIC_SUFFIX_NOTIFY = "ota/notify";
    private static final String TOPIC_SUFFIX_CANCEL = "ota/cancel";
    private static final String TOPIC_SUFFIX_ROLLBACK = "ota/rollback";

    private final CommHubFeignClient commHubFeignClient;

    @Override
    public boolean notifyDevice(OtaTaskDevice taskDevice, OtaFirmware firmware) {
        return publish(taskDevice, TOPIC_SUFFIX_NOTIFY, buildNotifyPayload(taskDevice, firmware));
    }

    @Override
    public boolean notifyCancel(OtaTaskDevice taskDevice) {
        return publish(taskDevice, TOPIC_SUFFIX_CANCEL, Map.of("taskId", taskDevice.getTaskId()));
    }

    @Override
    public boolean notifyRollback(OtaTaskDevice taskDevice, OtaFirmware firmware) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskDevice.getTaskId());
        if (StringUtils.hasText(firmware.getRollbackCommand())) {
            payload.put("rollbackCommand", firmware.getRollbackCommand());
        }
        return publish(taskDevice, TOPIC_SUFFIX_ROLLBACK, payload);
    }

    private boolean publish(OtaTaskDevice taskDevice, String topicSuffix, Map<String, Object> payload) {
        MqttPublishRequest request = new MqttPublishRequest();
        request.setDeviceId(taskDevice.getDeviceId());
        request.setTopicSuffix(topicSuffix);
        request.setPayload(payload);
        request.setEncrypt(true);
        request.setQos(1);
        request.setWaitAck(false);
        try {
            Result<MqttPublishResult> result = commHubFeignClient.publish(request);
            boolean success = result != null && result.isSuccess();
            if (!success) {
                log.warn("[ota] {} 下发失败 taskId={} deviceCode={} message={}",
                        topicSuffix, taskDevice.getTaskId(), taskDevice.getDeviceCode(),
                        result == null ? "无响应" : result.getMessage());
            }
            return success;
        } catch (Exception e) {
            log.warn("[ota] {} 下发异常 taskId={} deviceCode={}: {}",
                    topicSuffix, taskDevice.getTaskId(), taskDevice.getDeviceCode(), e.getMessage());
            return false;
        }
    }

    private Map<String, Object> buildNotifyPayload(OtaTaskDevice taskDevice, OtaFirmware firmware) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("taskId", taskDevice.getTaskId());
        payload.put("packageId", firmware.getPackageId());
        payload.put("version", firmware.getVersion());
        payload.put("downloadUrl", firmware.getDownloadUrl());
        payload.put("sha256", firmware.getSha256());
        payload.put("keyId", firmware.getKeyId());
        if (StringUtils.hasText(firmware.getSigOssPath())) {
            payload.put("sigFileUrl", firmware.getSigOssPath());
        }
        if (StringUtils.hasText(firmware.getSigLocalPath())) {
            payload.put("sigFilePath", firmware.getSigLocalPath());
        }
        if (StringUtils.hasText(firmware.getInstallCommand())) {
            payload.put("installCommand", firmware.getInstallCommand());
        }
        if (StringUtils.hasText(firmware.getRollbackCommand())) {
            payload.put("rollbackCommand", firmware.getRollbackCommand());
        }
        if (StringUtils.hasText(firmware.getHealthcheckCommand())) {
            payload.put("healthcheckCommand", firmware.getHealthcheckCommand());
        }
        payload.put("cancelableInExecuting", Boolean.TRUE.equals(firmware.getCancelableInExecuting()));
        return payload;
    }
}
