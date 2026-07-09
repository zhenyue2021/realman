package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** 对应 POST /api/v1/devices/test-flag/batch。批量只支持标记（true），取消标记须逐台走单台接口以保留二次确认。 */
@Data
@Schema(description = "批量测试设备标记请求")
public class TestFlagBatchRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotEmpty
    private List<String> deviceCodes;

    @NotNull
    private Boolean testDevice;
}
