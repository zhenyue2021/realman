package org.jeecg.modules.ota.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@Schema(description = "升级任务列表查询条件（PRD 9.5.2）")
public class TaskListQuery implements Serializable {

    private static final long serialVersionUID = 1L;

    private Integer pageNo = 1;

    private Integer pageSize = 20;

    private String deviceType;

    private String status;

    private LocalDateTime createdAtFrom;

    private LocalDateTime createdAtTo;

    /** 非超管调用时由控制器注入，限定只能看本租户任务 */
    private String tenantId;
}
