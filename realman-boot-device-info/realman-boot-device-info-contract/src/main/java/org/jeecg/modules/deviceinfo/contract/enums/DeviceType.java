package org.jeecg.modules.deviceinfo.contract.enums;

/**
 * 设备类型。对齐《达尔文设备升级平台 PRD V1.0.0》第 3 章设备角色定义与 4.1.3 Smart Arm。
 */
public enum DeviceType {
    /** 遥操设备（主控端） */
    MASTER,
    /** 机器人 */
    SLAVE,
    /** Smart Arm（app/model/fw 三组件版本） */
    SMART_ARM
}
