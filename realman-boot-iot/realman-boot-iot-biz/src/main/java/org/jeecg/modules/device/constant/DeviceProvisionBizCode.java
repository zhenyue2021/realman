package org.jeecg.modules.device.constant;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 设备 HTTP 自注册（Provision）业务码。
 *
 * <p>外层 {@code ApiResult.code/message} 仅表示传输层（固定 200 / ok）；
 * 设备端应依据本枚举的 {@code code} 做分支判断。
 */
@Getter
@RequiredArgsConstructor
public enum DeviceProvisionBizCode {

    REGISTERED_NEW(0, "设备注册成功"),
    ALREADY_REGISTERED(1, "设备已注册"),

    PROVISION_DISABLED(40001, "设备自注册功能未启用"),
    TIMESTAMP_EXPIRED(40002, "请求已过期，请校准设备时间后重试"),
    SIGN_INVALID(40003, "签名校验失败"),
    DEVICE_TYPE_INVALID(40004, "deviceType 不合法，仅支持 1(机器人) 或 2(主控)"),
    DEVICE_DISABLED(40005, "设备已禁用，无法注册: %s"),
    VALIDATION_ERROR(40006, "%s");

    private final int code;
    private final String messageTemplate;

    public String formatMessage(Object... args) {
        if (args == null || args.length == 0) {
            return messageTemplate;
        }
        return String.format(messageTemplate, args);
    }

    public static boolean isSuccess(int bizCode) {
        return bizCode < 40000;
    }
}
