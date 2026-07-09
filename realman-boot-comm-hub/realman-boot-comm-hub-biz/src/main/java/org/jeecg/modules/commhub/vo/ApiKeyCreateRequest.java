package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "创建 HTTP-MQTT 桥接 API Key 请求")
public class ApiKeyCreateRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String tenantId;

    @Schema(description = "可操作的 deviceId 列表，缺省/空表示不限设备（仍受 tenantId 约束）")
    private List<String> deviceScope;

    @Schema(description = "可下发的 topicSuffix 列表（支持 xxx/* 前缀通配），缺省/空表示不限 Topic")
    private List<String> topicSuffixScope;
}
