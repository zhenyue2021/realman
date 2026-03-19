package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 停止遥操请求参数
 */
@Data
public class TeleopStopDTO {
    /** 机器人设备ID（二选一） */
    private String deviceId;
    /** 机器人设备编码（二选一） */
    private String deviceCode;
    /** 操作人（可选） */
    private String operator;
}

