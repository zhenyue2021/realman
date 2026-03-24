package org.jeecg.modules.device.dto.workorder;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import org.jeecg.common.aspect.annotation.Dict;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 工单合规配置page vo
 */
@Data
public class WorkOrderComplianceConfigPageVo implements Serializable {

    private String id;

    private String agentId;

    private String agentName;

    private String enterpriseId;

    private String enterpriseName;

    private String taskScene;

    @Dict(dicCode = "enabled_status")
    private Integer timeoutAlertEnabled;

    private String timeoutAlertOffset;

    @Dict(dicCode = "enabled_status")
    private Integer taskLimitEnabled;

    @Dict(dicCode = "enabled_status")
    private Integer acceptanceEnabled;

    /** 是否启用超时提交：0-禁用 1-启用 */
    @Dict(dicCode = "enabled_status")
    private Integer overtimeEnabled;

    private String overtimeReasonEnum;

    private String overtimeReasonDesc;

    @Dict(dicCode = "enabled_status")
    private Integer autoCloseEnabled;

    private String autoCloseOffset;

    @Dict(dicCode = "apply_status")
    private Integer applyStatus;

    private String createBy;

    private LocalDateTime createTime;

    private String updateBy;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;

    private String tenantId;
}

