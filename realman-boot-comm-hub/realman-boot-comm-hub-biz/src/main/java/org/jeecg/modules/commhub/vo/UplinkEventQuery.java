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

    private String eventKind;

    private String tenantId;

    /** 授权设备范围，支持 deviceId 或 deviceCode；为空表示租户下不限设备。 */
    private List<String> deviceScope;

    private LocalDateTime since;
}
