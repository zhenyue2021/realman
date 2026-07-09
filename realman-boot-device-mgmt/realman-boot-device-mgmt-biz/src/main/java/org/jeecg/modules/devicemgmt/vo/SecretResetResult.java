package org.jeecg.modules.devicemgmt.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "MQTT 连接层密钥重置结果（明文仅此一次返回）")
public class SecretResetResult implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceSecret;

    private int deviceSecretVersion;
}
