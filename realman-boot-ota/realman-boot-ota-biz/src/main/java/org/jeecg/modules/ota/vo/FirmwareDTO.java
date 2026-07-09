package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Schema(description = "固件包（对应 PRD 4.2.3 固件包列表展示字段）")
public class FirmwareDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    private String packageId;

    private String firmwareFileName;

    private String deviceType;

    private String version;

    private String minVersion;

    private List<String> compatibleModels;

    private String installCommand;

    private String rollbackCommand;

    private String healthcheckCommand;

    private String riskLevel;

    private boolean cancelableInExecuting;

    private String sha256;

    private String keyId;

    /** ✔ 已签名 / 🚫 签名已吊销，前端自行渲染图标；此处只给状态字符串 ACTIVE/PENDING_ACTIVATION/REVOKED */
    private String keyStatus;

    private String storageSource;

    private Integer fileSizeMb;

    private String createdBy;

    private LocalDateTime createdAt;
}
