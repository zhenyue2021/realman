package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/** 上行事件轮询兜底查询条件，对应设备通信中台详细设计 4.3.2 轮询兜底通道。 */
@Data
@Schema(description = "上行事件查询条件")
public class UplinkEventQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String deviceId;

    /** 服务端注入的租户过滤条件，不允许第三方自行越权指定。 */
    private String tenantId;

    /** 服务端注入的设备范围，包含 deviceId 或 deviceCode；null 表示不限制。 */
    private List<String> authorizedDevices;

    /** 稳定增量游标：只返回 id 大于该值的事件。 */
    private String afterId;

    private String eventKind;

    private LocalDateTime since;
}
