package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 对应 PUT /api/v1/devices/{deviceId}/test-flag。
 * 标记（testDevice=true）不要求二次确认；取消标记（testDevice=false）要求
 * confirmText=UNSET_TEST_FLAG，防止"标记→升级→取消标记"绕过高风险管控
 * （对齐设备基座详细设计 3.5 测试设备取消标记时序图）。
 */
@Data
@Schema(description = "测试设备标记变更请求")
public class TestFlagRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotNull
    private Boolean testDevice;

    /** 仅取消标记（testDevice=false）时校验 */
    private String confirmText;
}
