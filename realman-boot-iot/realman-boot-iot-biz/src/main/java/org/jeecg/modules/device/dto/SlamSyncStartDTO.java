package org.jeecg.modules.device.dto;

import lombok.Data;

import java.util.List;

@Data
public class SlamSyncStartDTO {
    private String sourceRobotId;
    private String slamMapId;
    private String enterpriseId;
    private List<String> targetRobotIds;
}

