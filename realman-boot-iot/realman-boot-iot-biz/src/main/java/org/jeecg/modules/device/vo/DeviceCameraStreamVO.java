package org.jeecg.modules.device.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 单路摄像头视频流信息（接口返回给 Web 端）
 *
 * <p>这是平台对机器人上报的 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.CameraInfo}
 * 进行轻量封装后的视图对象，仅保留前端展示与播放所需字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceCameraStreamVO {
    /** 摄像头路数索引（从 0 开始） */
    private Integer cameraIndex;
    /** 摄像头名称/标识 */
    private String cameraName;
    /** 视频流地址（RTSP/RTMP/HLS 等） */
    private String streamUrl;
    /** 流类型（如 rtsp、rtmp、hls） */
    private String streamType;
}
