package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.mapper.IotRobotSlamBindingMapper;
import org.jeecg.modules.device.mapper.IotSlamMapMapper;
import org.jeecg.modules.device.util.MinioUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 设备上线后补推 SLAM 同步命令。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RefreshScope
public class SlamPendingSyncService {

    private final IotRobotSlamBindingMapper bindingMapper;
    private final IotSlamMapMapper slamMapMapper;
    @Lazy
    private final MqttPublisher mqttPublisher;
    private final MinioClient minioClient;
    private final MinioUtil minioUtil;
    private final ObjectMapper objectMapper;

    @Value("${minio.bucket-name.slam:iot-slam}")
    private String bucketName;

    public void flushPendingSyncCommands(String deviceCode) {
        List<IotRobotSlamBinding> pendingBindings = bindingMapper.selectList(
                new LambdaQueryWrapper<IotRobotSlamBinding>()
                        .eq(IotRobotSlamBinding::getRobotCode, deviceCode)
                        .eq(IotRobotSlamBinding::getState, DeviceConstant.SlamBindingState.PENDING)
                        .eq(IotRobotSlamBinding::getDelFlag, 0)
                        .orderByAsc(IotRobotSlamBinding::getCreateTime));
        if (pendingBindings == null || pendingBindings.isEmpty()) {
            return;
        }

        int pushed = 0;
        for (IotRobotSlamBinding binding : pendingBindings) {
            try {
                IotSlamMap map = slamMapMapper.selectById(binding.getSlamMapId());
                if (map == null || map.getDelFlag() != null && map.getDelFlag() == 1) {
                    continue;
                }
                if (!Integer.valueOf(DeviceConstant.SlamMapStatus.READY).equals(map.getStatus())) {
                    continue;
                }

                MqttMessageModel.SlamSyncCommand cmd = MqttMessageModel.SlamSyncCommand.builder()
                        .taskId(binding.getPendingTaskId())
                        .bindingId(binding.getId())
                        .slamMapId(map.getId())
                        .sourceRobotCode(map.getSourceRobotCode())
                        .objectKey(map.getFileObjectKey())
                        .getUrl(presignGetUrl(map.getFileObjectKey(), DeviceConstant.Timeout.SLAM_UPLOAD_PERMIT_MINUTES))
                        .md5(map.getFileMd5())
                        .size(map.getFileSize())
                        .timestamp(System.currentTimeMillis())
                        .build();

                mqttPublisher.publishToDevice(deviceCode,
                        String.format(DeviceConstant.MqttTopic.SLAM_SYNC_COMMAND, deviceCode),
                        objectMapper.writeValueAsString(cmd), 1);
                pushed++;
            } catch (Exception e) {
                log.warn("[SlamPendingSync] 补推失败 deviceCode={}, bindingId={}, err={}",
                        deviceCode, binding.getId(), e.getMessage());
            }
        }
        if (pushed > 0) {
            log.info("[SlamPendingSync] 设备[{}]上线，补推{}条SLAM同步指令", deviceCode, pushed);
        }
    }

    private String presignGetUrl(String objectKey, long minutes) {
        try {
            minioUtil.ensureBucketExists(bucketName);
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucketName)
                    .object(objectKey)
                    .expiry((int) minutes, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new RuntimeException("生成SLAM下载URL失败: " + e.getMessage(), e);
        }
    }
}

