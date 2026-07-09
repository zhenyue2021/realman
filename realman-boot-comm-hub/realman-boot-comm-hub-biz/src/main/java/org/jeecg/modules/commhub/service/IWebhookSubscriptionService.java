package org.jeecg.modules.commhub.service;

import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateRequest;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionDTO;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionListQuery;

public interface IWebhookSubscriptionService {

    WebhookSubscriptionCreateResult create(WebhookSubscriptionCreateRequest request, String operator);

    PageResult<WebhookSubscriptionDTO> list(WebhookSubscriptionListQuery query);

    /** 手动停用，与连续失败自动置为 PAUSED 是两回事——手动停用后不能靠 resume 恢复，需重新创建。 */
    void disable(String id);

    /** 恢复因连续投递失败被自动置为 PAUSED 的订阅，清零失败计数，见设备通信中台详细设计 4.3.2。 */
    void resume(String id);

    /** 记录一次投递结果：成功清零失败计数；失败累加，达到阈值自动置为 PAUSED 并告警日志。 */
    void recordDispatchResult(String subscriptionId, boolean success);
}
