package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 开始遥操请求参数
 */
@Data
public class TeleopStartDTO {
    /** 机器人设备ID（必填） */
    private String deviceId;
    /** 操作人（可选） */
    private String operator;
}

