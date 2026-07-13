package org.jeecg.modules.commhub.vo;

import lombok.Data;

import java.io.Serializable;

/** API Key 解析后的授权范围，用于桥接以外的轮询/订阅等只读能力。 */
@Data
public class ApiKeyScope implements Serializable {

    private static final long serialVersionUID = 1L;

    private String apiKeyId;

    private String tenantId;

    private String deviceScope;
}
