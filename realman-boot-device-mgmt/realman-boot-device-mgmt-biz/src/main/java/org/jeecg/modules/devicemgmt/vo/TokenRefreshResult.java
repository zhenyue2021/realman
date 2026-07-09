package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "Token 续签结果")
public class TokenRefreshResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceToken;

    private LocalDateTime tokenExpiresAt;
}
