package org.jeecg.modules.deviceinfo.contract.enums;

/** 设备四态。对齐业务架构 v1.2"设备生命周期与四态管理"：空闲/休眠/占用/离线。 */
public enum OccupancyState {
    IDLE,
    SLEEP,
    OCCUPIED,
    OFFLINE
}
