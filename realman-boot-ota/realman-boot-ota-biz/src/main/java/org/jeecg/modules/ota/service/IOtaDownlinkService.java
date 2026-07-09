package org.jeecg.modules.ota.service;

import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTaskDevice;

/**
 * 下行任务通知，经设备通信中台统一下行发布，见 OTA 平台详细设计第二章
 * 协议映射表（{@code device/{code}/ota/notify}）。
 */
public interface IOtaDownlinkService {

    /**
     * 下发升级通知。返回 {@code true} 表示下发成功（后台侧视角，不代表设备已收到/确认），
     * {@code false} 表示下发失败（如通信中台不可用），调用方据此决定是否将子任务置 FAILED。
     */
    boolean notifyDevice(OtaTaskDevice taskDevice, OtaFirmware firmware);

    /** 下发取消指令（PRD 4.6.1）。 */
    boolean notifyCancel(OtaTaskDevice taskDevice);

    /** 下发手动回滚指令（PRD 4.6.1 软件手动回滚）。 */
    boolean notifyRollback(OtaTaskDevice taskDevice, OtaFirmware firmware);
}
