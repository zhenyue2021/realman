package org.jeecg.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运动与安全参数查询结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "运动与安全参数")
public class SportSpeedVO {

    @Schema(description = "底盘行进速度等级")
    private Integer moveSpeedLevel;

    @Schema(description = "身体升降速度等级")
    private Integer liftSpeedLevel;
}
