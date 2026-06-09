package org.jeecg.modules.integration.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 生成临时 Token 请求入参
 * <p>外部系统无需提供用户名密码，仅需标识自身来源（sourceSystem），
 * 内部由预置服务账号代为颁发 Token。
 */
@Data
public class TempTokenRequestDTO implements Serializable {

    /**
     * 调用方系统标识，必须为约定值（当前仅允许 "DEW"）
     */
    @NotBlank(message = "sourceSystem 不能为空")
    private String sourceSystem;

    /**
     * Token 有效期（秒），不传时默认 3600 秒（1小时）
     * <p>最小 60 秒，最大 86400 秒（24小时）
     */
    @Min(value = 60,    message = "expireSeconds 最小 60 秒")
    @Max(value = 86400, message = "expireSeconds 最大 86400 秒（24小时）")
    private int expireSeconds = 3600;
}
