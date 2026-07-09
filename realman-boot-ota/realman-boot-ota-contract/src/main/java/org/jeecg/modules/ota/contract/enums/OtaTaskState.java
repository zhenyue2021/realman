package org.jeecg.modules.ota.contract.enums;

/**
 * 升级任务 15 态状态机，对齐 OTA 平台详细设计第八章（逐字转译自
 * 《达尔文设备升级平台 PRD V1.0.0》第五章）。
 *
 * <p>{@code IN_PROGRESS} 不是独立状态值，是批量任务聚合视图的展示态，不出现在
 * 本枚举中，也不应写入单设备任务的状态字段。
 */
public enum OtaTaskState {

    /** 已创建，等待下发（在线设备） */
    PENDING,
    /** 目标设备离线，等待上线；超过 device_offline_timeout_hours 自动 FAILED */
    PENDING_ONLINE,
    /** 已下发指令，执行下发前二次校验（版本兼容性 + 签名吊销） */
    STARTING,
    /** 下载中（支持 HTTP Range 断点续传） */
    DOWNLOADING,
    /** SHA-256 完整性校验 + Ed25519 签名校验 */
    CHECKING,
    /** 解压至 releases/{version}/ 目录 */
    EXTRACTING,
    /** 执行安装：install_exec -&gt; symlink_switch -&gt; os_sync 三子阶段 */
    EXECUTING,
    /** 执行 healthcheck_command */
    HEALTH_CHECKING,
    /** 成功终态 */
    COMPLETED,
    /** 失败终态（不触发回滚，设备归 IDLE） */
    FAILED,
    /** 回滚中 */
    ROLLING_BACK,
    /** 回滚成功终态 */
    ROLLED_BACK,
    /** 回滚失败终态，需人工介入 */
    ROLLBACK_FAILED,
    /** 批量任务因失败阈值触发暂停，等待人工 resume/abort */
    PAUSED,
    /** 已取消终态 */
    CANCELLED
}
