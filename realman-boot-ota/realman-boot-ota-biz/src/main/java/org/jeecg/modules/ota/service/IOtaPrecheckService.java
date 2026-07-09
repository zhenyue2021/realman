package org.jeecg.modules.ota.service;

import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.ota.entity.OtaFirmware;

/**
 * 前置校验四分类，对齐 OTA 平台详细设计六章（PRD 4.4.2）：设备状态检查、资源检查、
 * 版本兼容性校验、签名吊销校验。版本兼容性与签名吊销校验在任务创建时与
 * {@code STARTING} 下发前各执行一次（双重校验），调用方在两个时间点分别调用。
 * 各方法校验不通过时抛出 {@link org.jeecg.common.exception.JeecgBootBizTipException}，
 * 异常信息以对应错误码开头（见 OtaErrorCode）。
 */
public interface IOtaPrecheckService {

    /**
     * 设备状态检查。{@code OFFLINE} 不算失败，由调用方另行处理为 PENDING_ONLINE，
     * 因此本方法只对"在线但不可升级"的场景抛异常，对离线设备直接放行（不检查其他项）。
     */
    void checkDeviceState(DeviceInfoDTO device);

    boolean isOffline(DeviceInfoDTO device);

    void checkResources(DeviceInfoDTO device);

    /** 磁盘可用空间 ≥ 固件包大小 × 2；需要目标固件信息，单独暴露供任务创建流程调用。 */
    void checkDiskSpaceForFirmware(DeviceInfoDTO device, int firmwareFileSizeMb);

    void checkVersionCompatibility(DeviceInfoDTO device, OtaFirmware firmware);

    void checkSignatureNotRevoked(OtaFirmware firmware);
}
