package org.jeecg.modules.device.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 力反馈参数查询结果
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "力反馈参数")
public class ForceFeedbackVO {

    @Schema(description = "机械臂力度等级")
    private Integer armLevel;

    @Schema(description = "夹爪力度等级")
    private Integer gripperLevel;
}
