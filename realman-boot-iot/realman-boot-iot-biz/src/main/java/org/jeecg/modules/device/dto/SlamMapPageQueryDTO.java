package org.jeecg.modules.device.dto;

import lombok.Data;

@Data
public class SlamMapPageQueryDTO {
    private Integer pageNo;
    private Integer pageSize;
    private String sourceRobotId;
    private String mapName;
    private Integer status;
}

