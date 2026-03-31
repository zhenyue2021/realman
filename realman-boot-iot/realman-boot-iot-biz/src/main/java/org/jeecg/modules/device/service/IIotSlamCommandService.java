package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotSlamCommandRecord;
import org.jeecg.modules.device.mqtt.MqttMessageModel;

import java.util.Map;

public interface IIotSlamCommandService extends IService<IotSlamCommandRecord> {

    /**
     * 向设备发送 SLAM 指令并创建请求记录（status=PENDING）。
     *
     * @param deviceCode 设备编码
     * @param function   功能代码，见 {@code DeviceConstant.SlamFunction}
     * @param params     功能参数（可为 null）
     * @return 创建的记录
     */
    IotSlamCommandRecord sendCommand(String deviceCode, String function, Map<String, Object> params);

    /**
     * 处理设备上报的 slam/ack，更新对应记录状态。
     *
     * <p>状态流转：
     * <ul>
     *   <li>sequence &lt; total 且 success=true → PARTIAL</li>
     *   <li>sequence == total 且 success=true → COMPLETED</li>
     *   <li>success=false → FAILED</li>
     * </ul>
     */
    void handleAck(String deviceCode, MqttMessageModel.SlamAck ack);

    /**
     * 处理设备上报的 slam/states，将最新状态写入 Redis（非持久化）。
     */
    void handleStates(String deviceCode, MqttMessageModel.SlamStates states);

    /**
     * 分页查询指定设备的 SLAM 指令记录，按发送时间倒序。
     */
    IPage<IotSlamCommandRecord> pageRecords(Page<IotSlamCommandRecord> page, String deviceCode);
}
