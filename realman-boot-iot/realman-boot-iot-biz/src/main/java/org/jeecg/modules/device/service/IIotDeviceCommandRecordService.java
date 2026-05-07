package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotDeviceCommandRecord;

public interface IIotDeviceCommandRecordService extends IService<IotDeviceCommandRecord> {

    /**
     * 记录指令下发，初始状态 PENDING。
     * 异步写入，不阻塞发送主流程。
     *
     * @param paramsJson 下发的明文 JSON（序列化后、AES 加密前）
     */
    void recordSend(String commandId, String deviceId, String deviceCode,
                    String commandType, String deviceType, String operator,
                    String paramsJson);

    /**
     * 收到设备 ACK 后更新状态、ackTime 和响应报文。
     * 若 commandId 为空或记录已非 PENDING 则静默忽略。
     *
     * @param ackDataJson 设备回复的完整明文 JSON
     */
    void ack(String commandId, boolean success, String failReason, String ackDataJson);

    /**
     * 批量将超过 {@link org.jeecg.modules.device.constant.DeviceConstant.Timeout#COMMAND_ACK_TIMEOUT_SECONDS}
     * 秒仍处于 PENDING 的记录标记为 TIMEOUT。
     *
     * @return 本次标记的记录数
     */
    int markTimeout();
}
