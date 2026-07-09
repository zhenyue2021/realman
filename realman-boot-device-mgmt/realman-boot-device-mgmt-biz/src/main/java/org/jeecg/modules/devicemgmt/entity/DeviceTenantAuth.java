package org.jeecg.modules.devicemgmt.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 设备-租户授权变更历史。对齐设备基座详细设计 3.2：{@code device_info.tenant_id}
 * 是当前生效租户，本表记录授权变更过程（谁在何时把设备授权给哪个租户，有效期）。
 */
@Data
@TableName("device_tenant_auth")
public class DeviceTenantAuth implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.ASSIGN_ID)
    private String id;

    @TableField("device_id")
    private String deviceId;

    @TableField("tenant_id")
    private String tenantId;

    @TableField("granted_by")
    private String grantedBy;

    @TableField(value = "granted_at", fill = FieldFill.INSERT)
    private LocalDateTime grantedAt;

    /** 有效期，可空表示长期有效 */
    @TableField("valid_until")
    private LocalDateTime validUntil;
}
