package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 上行事件轮询兜底查询条件，对应设备通信中台详细设计 4.3.2 轮询兜底通道。 */
@Data
@Schema(description = "上行事件查询条件")
public class UplinkEventQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String deviceId;

    private String eventKind;

    private LocalDateTime since;

    /** 稳定消费游标：只返回 id 大于该值的事件；reportedAt 仅作为业务时间过滤。 */
    private String afterId;
}
