package org.jeecg.modules.device.service.impl.device;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson.JSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.MqttConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.jeecg.modules.device.stream.ZlMediaKitPlayUrlClient;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 摄像头流：MQTT 查询 + 同步等待 ACK + ZLM 播放地址解析。
 */
@Slf4j
@Service
@RefreshScope
@RequiredArgsConstructor
public class IotDeviceCameraStreamService {

    private final IotDeviceSupport deviceSupport;
    private final DeviceCameraStreamPendingService deviceCameraStreamPendingService;
    private final ZlMediaKitPlayUrlClient zlMediaKitPlayUrlClient;
    private final MqttPublisher mqttPublisher;
    private final ObjectMapper objectMapper;

    @Value("${device.stream.host:172.16.44.66}")
    private String host;
    @Value("${device.stream.port.push:554}")
    private String pushPort;
    @Value("${device.stream.app:live}")
    private String app;

    public List<DeviceCameraStreamVO> getCameraStreams(String deviceId, Integer cameraIndex) {
        List<DeviceCameraStreamVO> result = new ArrayList<>();
        List<DeviceCameraStreamVO> sourceResult = new ArrayList<>();
        IotDevice device = deviceSupport.require(deviceId);
        if (DeviceConstant.DeviceStatus.ONLINE != device.getStatus()) {
            throw new RuntimeException("设备不在线");
        }

        String commandId = IdUtil.fastSimpleUUID();
        CompletableFuture<List<MqttMessageModel.CameraInfo>> future =
                deviceCameraStreamPendingService.register(commandId);

        try {
            MqttMessageModel.CameraStreamQuery query = MqttMessageModel.CameraStreamQuery.builder()
                    .commandId(commandId)
                    .cameraIndex(cameraIndex)
                    .host(host)
                    .port(pushPort)
                    .app(app)
                    .timestamp(System.currentTimeMillis())
                    .build();
            String payload = objectMapper.writeValueAsString(query);
            String topic = String.format(DeviceConstant.MqttTopic.CAMERA_STREAM_QUERY, device.getDeviceCode());
            mqttPublisher.publishToDevice(device.getDeviceCode(), topic, payload, MqttConstant.MQTT_QOS.QOS_1);
        } catch (Exception e) {
            deviceCameraStreamPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("摄像头流查询指令发送失败: " + e.getMessage(), e);
        }

        try {
            List<MqttMessageModel.CameraInfo> cameras = future.get(10, TimeUnit.SECONDS);
            log.debug("设备 {} 摄像头流查询结果：{}", deviceId, JSON.toJSONString(cameras));
            sourceResult = cameras.stream()
                    .map(c -> DeviceCameraStreamVO.builder()
                            .cameraIndex(c.getCameraIndex())
                            .cameraName(c.getCameraName())
                            .streamUrl(c.getStream())
                            .build())
                    .collect(Collectors.toList());
        } catch (java.util.concurrent.TimeoutException e) {
            deviceCameraStreamPendingService.completeExceptionally(commandId, e);
            throw new RuntimeException("等待摄像头流地址超时（10s），设备未响应");
        } catch (Exception e) {
            throw new RuntimeException("获取摄像头流地址失败: " + e.getMessage(), e);
        }
        if (CollectionUtil.isNotEmpty(sourceResult)) {
            sourceResult.forEach(c -> {
                if (StrUtil.isNotBlank(c.getStreamUrl())) {
                    String playUrl = zlMediaKitPlayUrlClient.resolveHlsPlayUrlIfStreamOnline(c.getStreamUrl());
                    result.add(DeviceCameraStreamVO.builder()
                            .cameraIndex(c.getCameraIndex())
                            .cameraName(c.getCameraName())
                            .streamUrl(playUrl)
                            .build());
                } else {
                    result.add(c);
                }
            });
        }
        return result;
    }
}
