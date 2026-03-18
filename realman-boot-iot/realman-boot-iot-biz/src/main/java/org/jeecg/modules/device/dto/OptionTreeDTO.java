package org.jeecg.modules.device.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 树形下拉选项
 */
@Data
public class OptionTreeDTO {
    private String id;
    private String name;
    private List<OptionTreeDTO> children = new ArrayList<>();

    public OptionTreeDTO() {
    }

    public OptionTreeDTO(String id, String name) {
        this.id = id;
        this.name = name;
    }
}

