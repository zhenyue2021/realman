package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.device.dto.SlamBindingPageQueryDTO;
import org.jeecg.modules.device.dto.SlamMapDetailDTO;
import org.jeecg.modules.device.dto.SlamMapPageQueryDTO;
import org.jeecg.modules.device.dto.SlamSyncTaskDetailDTO;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.mqtt.MqttMessageModel;

import java.util.List;

public interface IIotSlamService {
    IPage<IotSlamMap> pageMaps(String tenantId, SlamMapPageQueryDTO query);

    SlamMapDetailDTO mapDetail(String mapId);

    IPage<IotRobotSlamBinding> pageBindings(String tenantId, SlamBindingPageQueryDTO query);

    String startSync(String tenantId, String operator, String enterpriseId, String sourceRobotId, String slamMapId, List<String> targetRobotIds);

    SlamSyncTaskDetailDTO taskDetail(String taskId);

    MqttMessageModel.SlamUploadPermit handleUploadRequest(String deviceCode, MqttMessageModel.SlamUploadRequest req);

    void handleUploadComplete(String deviceCode, MqttMessageModel.SlamUploadComplete req);

    void handleSyncAck(String deviceCode, MqttMessageModel.SlamSyncAck ack);
}

