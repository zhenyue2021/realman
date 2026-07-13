package org.jeecg.modules.commhub.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.vo.ApiKeyCreateRequest;
import org.jeecg.modules.commhub.vo.ApiKeyCreateResult;
import org.jeecg.modules.commhub.vo.ApiKeyDTO;
import org.jeecg.modules.commhub.vo.ApiKeyListQuery;
import org.jeecg.modules.commhub.vo.ApiKeyScope;

/**
 * HTTP-MQTT 桥接第三方系统身份管理，见设备通信中台详细设计 4.3.1/4.5。
 */
public interface IApiKeyService {

    ApiKeyCreateResult create(ApiKeyCreateRequest request, String operator);

    PageResult<ApiKeyDTO> list(ApiKeyListQuery query);

    void revoke(String id);

    /**
     * 校验 API Key 是否有权限对指定设备下发指定 Topic 后缀，通过后返回该 Key 的内部
     * id（供调用方按 Key 维度限流，见 {@code BridgeRateLimitService}）。未通过时抛出
     * {@link org.jeecg.common.exception.JeecgBootBizTipException}
     * （{@code ERR_API_KEY_INVALID}/{@code ERR_API_KEY_UNAUTHORIZED}）。
     */
    String assertAuthorized(String rawApiKey, String deviceId, String topicSuffix);

    /** 校验 API Key 有效性并返回其租户/设备范围，供只读轮询类接口做租户隔离。 */
    ApiKeyScope resolveScope(String rawApiKey);
}
