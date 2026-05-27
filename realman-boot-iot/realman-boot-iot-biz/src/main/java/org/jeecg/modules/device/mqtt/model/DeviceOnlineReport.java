package org.jeecg.modules.device.mqtt.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 机器人设备上线信息上报（Topic: device/{deviceCode}/datacollect/deviceOnline）
 *
 * @see org.jeecg.modules.device.mqtt.MqttMessageModel.DeviceOnlineReport 兼容别名
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DeviceOnlineReport {
    private long timestamp;
    private String deviceSn;
    private OnlinePayload payload;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class OnlinePayload {
        private String deviceType;
        private String version;
        private Location location;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Location {
        private Double latitude;
        private Double longitude;
    }
}
