package org.jeecg.modules.integration.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

/**
 * 外部系统参数传输入参（通用承载）
 */
@Data
public class ExternalParamReceiveDTO implements Serializable {

    /**
     * 外部系统编码（用于区分调用方）
     */
    @NotBlank(message = "sourceSystem不能为空")
    private String sourceSystem;

    /**
     * 内部系统编码（用于区分调用方）
     */
    @NotBlank(message = "targetSystem不能为空")
    private String targetSystem;

    /**
     * 本次请求唯一标识（便于幂等与排查）
     */
    @NotBlank(message = "requestId不能为空")
    private String requestId;

    /**
     * 业务类型/场景码（你们双方约定）
     */
    @NotBlank(message = "bizType不能为空")
    private String bizType;

    /**
     * 业务参数（可承载任意 JSON 对象）
     */
    @NotNull(message = "params不能为空")
    private Map<String, Object> params;
}

