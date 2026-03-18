package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 通用下拉选项（轻量，避免跨模块依赖 system DTO）
 */
@Data
public class OptionDTO {
    private String id;
    private String name;

    public OptionDTO() {
    }

    public OptionDTO(String id, String name) {
        this.id = id;
        this.name = name;
    }
}

