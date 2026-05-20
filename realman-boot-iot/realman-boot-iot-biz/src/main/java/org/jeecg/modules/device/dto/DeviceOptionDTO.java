package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 通用下拉选项（轻量，避免跨模块依赖 system DTO）
 */
@Data
public class DeviceOptionDTO {
    private String id;
    private String code;
    private String name;

    public DeviceOptionDTO() {
    }

    public DeviceOptionDTO(String id, String code, String name) {
        this.id = id;
        this.code = code;
        this.name = name;
    }
}

