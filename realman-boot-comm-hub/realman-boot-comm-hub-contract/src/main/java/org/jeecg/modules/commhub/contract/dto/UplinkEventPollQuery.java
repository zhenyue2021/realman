package org.jeecg.modules.commhub.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 内部轮询查询上行事件，供 OTA 等业务服务消费 {@code DeviceUplinkEvent}
 * （见通信中台详细设计 4.3.2、OTA 平台详细设计第二章协议映射表）。
 * 与面向第三方的 {@code GET /api/v1/devices/uplink-events} 是同一份落库数据，
 * 只是走内部 Feign 而非对外网关。
 */
@Data
@Schema(description = "上行事件内部轮询查询条件")
public class UplinkEventPollQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "事件种类过滤，缺省不过滤")
    private String eventKind;

    @Schema(description = "只返回该时间之后上报的事件（不含等于），缺省不过滤；推荐新消费方优先使用 afterId")
    private LocalDateTime since;

    @Schema(description = "稳定增量游标：只返回 id 大于该值的事件，避免 reportedAt 同秒重复/漏读")
    private String afterId;

    @Schema(description = "单次最多返回条数，默认 200")
    private Integer limit = 200;
}
