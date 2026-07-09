package org.jeecg.modules.ota.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 系统设置键值表，对齐 OTA 平台详细设计十章（PRD 9.9，17 项字段）。
 * 键名与默认值见 {@link org.jeecg.modules.ota.config.OtaSystemSettingDefaults}。
 */
@Data
@TableName("ota_system_setting")
public class OtaSystemSetting implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId("setting_key")
    private String settingKey;

    @TableField("setting_value")
    private String settingValue;

    @TableField("updated_by")
    private String updatedBy;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
