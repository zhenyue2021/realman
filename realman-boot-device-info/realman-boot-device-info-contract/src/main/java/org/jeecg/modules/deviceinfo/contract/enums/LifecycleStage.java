package org.jeecg.modules.deviceinfo.contract.enums;

/** 设备全生命周期阶段：出厂 → 激活 → 运行 → 维修 → 退役。 */
public enum LifecycleStage {
    MANUFACTURED,
    ACTIVATED,
    RUNNING,
    MAINTENANCE,
    RETIRED
}
