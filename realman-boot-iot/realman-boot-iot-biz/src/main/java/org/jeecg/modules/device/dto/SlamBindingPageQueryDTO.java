package org.jeecg.modules.device.dto;

import lombok.Data;

@Data
public class SlamBindingPageQueryDTO {
    private Integer pageNo;
    private Integer pageSize;
    private String robotId;
    private String slamMapId;
    private Integer state;
}

