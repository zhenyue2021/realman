package org.jeecg.modules.commhub.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Schema(description = "上行事件记录")
public class UplinkEventDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;

    private String deviceId;

    private String deviceCode;

    private String deviceType;

    private String tenantId;

    private String eventKind;

    private String transport;

    private Map<String, Object> payload;

    private LocalDateTime reportedAt;
}
