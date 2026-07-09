package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/** 对应 POST /api/v1/admin/devices/migrate-from-legacy-iot，须输入确认文本 MIGRATE_LEGACY_DEVICES。 */
@Data
@Schema(description = "存量设备迁移请求")
public class LegacyDeviceMigrationRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank
    private String confirmText;
}
