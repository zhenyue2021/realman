package org.jeecg.modules.commhub.contract.event;

/**
 * {@link DeviceUplinkEvent} 的事件种类。见设备通信中台详细设计 5.1/5.2、
 * 平台能力清单第八章。
 */
public enum EventKind {
    HEARTBEAT,
    OTA_PROGRESS,
    OTA_STATUS_REPORT,
    ONLINE,
    OFFLINE,
    REGISTER,
    TOKEN_REFRESH
}
