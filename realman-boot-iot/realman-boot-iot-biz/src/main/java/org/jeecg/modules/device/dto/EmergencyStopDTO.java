package org.jeecg.modules.device.dto;

import lombok.Data;

/**
 * 紧急停机指令请求体
 */
@Data
public class EmergencyStopDTO {
    /** 停机原因 */
    private String reason;
    /** 操作人 */
    private String operator;
}

