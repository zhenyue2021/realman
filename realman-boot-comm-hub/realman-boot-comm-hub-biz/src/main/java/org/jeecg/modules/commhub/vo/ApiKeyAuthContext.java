package org.jeecg.modules.commhub.vo;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

/** API Key 认证后的调用方上下文，供第三方接口按租户与设备授权范围收敛数据。 */
@Data
public class ApiKeyAuthContext implements Serializable {

    private static final long serialVersionUID = 1L;

    private String apiKeyId;

    private String tenantId;

    /** 原始 deviceScope 解析后的条目；为空表示不限设备。 */
    private List<String> deviceScope;
}
