package org.jeecg.modules.commhub.contract.event;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 统一上行事件模型。无论数据来自设备端向 MQTT 报文体、设备端向 HTTP 自注册请求，
 * 还是 WEB 端向网关收到的 HTTP 请求，接入层都归一化为这个对象，供下游（OTA 平台、
 * 设备基座、状态监控等）统一消费，也供 HTTP-MQTT 桥接的 Webhook/轮询转发给第三方。
 *
 * <p>见设备通信中台详细设计 5.1；不是对外 REST 资源，而是内部 Feign/事件契约中
 * 反复出现的公共载荷类型，因此单独放在 {@code event} 包而不是 {@code dto} 包。
 */
@Data
@Schema(description = "设备上行事件（统一归一化模型）")
public class DeviceUplinkEvent implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "上行事件日志 ID；内部轮询消费推荐用作稳定游标")
    private String eventId;

    @Schema(description = "设备内部唯一标识（UUID）")
    private String deviceId;

    @Schema(description = "设备序列号 / 通信层标识")
    private String deviceCode;

    private DeviceType deviceType;

    private String tenantId;

    private EventKind eventKind;

    @Schema(description = "原始传输介质，绝大多数事件恒为 MQTT，仅 REGISTER 取值 HTTP")
    private Transport transport;

    @Schema(description = "事件载荷，具体结构随 eventKind 变化，由消费方自行解析")
    private Map<String, Object> payload;

    @Schema(description = "设备侧上报时间戳")
    private LocalDateTime reportedAt;
}
