package org.jeecg.modules.devicemgmt.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

/**
 * MQTT ACL 规则。对应 ADR-0001 规划的 {@code GET /internal/device/{code}/acl-rules}，
 * 供设备通信中台的 EMQX ACL 回调使用。
 */
@Data
@Schema(description = "设备 MQTT ACL 规则")
public class DeviceAclRuleDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "Topic 过滤模式，如 device/{code}/#")
    private String topicPattern;

    @Schema(description = "允许的操作：SUBSCRIBE / PUBLISH / ALL")
    private String action;
}
