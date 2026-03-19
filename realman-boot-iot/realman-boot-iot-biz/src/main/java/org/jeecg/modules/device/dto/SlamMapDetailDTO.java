package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;

import java.util.List;

@Data
public class SlamMapDetailDTO {
    private IotSlamMap map;
    private List<IotRobotSlamBinding> bindings;
}

