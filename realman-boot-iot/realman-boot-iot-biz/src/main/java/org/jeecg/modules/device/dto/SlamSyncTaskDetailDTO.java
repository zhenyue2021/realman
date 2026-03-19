package org.jeecg.modules.device.dto;

import lombok.Data;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamSyncTask;

import java.util.List;

@Data
public class SlamSyncTaskDetailDTO {
    private IotSlamSyncTask task;
    private List<IotRobotSlamBinding> bindings;
}

