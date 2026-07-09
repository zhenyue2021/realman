package org.jeecg.modules.commhub.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateRequest;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionDTO;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionListQuery;

public interface IWebhookSubscriptionService {

    WebhookSubscriptionCreateResult create(WebhookSubscriptionCreateRequest request, String operator);

    PageResult<WebhookSubscriptionDTO> list(WebhookSubscriptionListQuery query);

    void disable(String id);
}
