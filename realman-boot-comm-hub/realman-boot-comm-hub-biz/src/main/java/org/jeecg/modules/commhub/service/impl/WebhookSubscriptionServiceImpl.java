package org.jeecg.modules.commhub.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.entity.WebhookSubscription;
import org.jeecg.modules.commhub.mapper.WebhookSubscriptionMapper;
import org.jeecg.modules.commhub.service.IWebhookSubscriptionService;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateRequest;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionCreateResult;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionDTO;
import org.jeecg.modules.commhub.vo.WebhookSubscriptionListQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookSubscriptionServiceImpl implements IWebhookSubscriptionService {

    private static final String ERR_SUBSCRIPTION_NOT_FOUND = "ERR_SUBSCRIPTION_NOT_FOUND";
    /** 连续投递失败达到该次数自动暂停，避免对一个长期不可达的回调地址无休止重试。 */
    private static final int AUTO_PAUSE_THRESHOLD = 5;

    private final WebhookSubscriptionMapper subscriptionMapper;

    @Override
    public WebhookSubscriptionCreateResult create(WebhookSubscriptionCreateRequest request, String operator) {
        String hmacSecret = IdUtil.fastSimpleUUID() + IdUtil.fastSimpleUUID();

        WebhookSubscription subscription = new WebhookSubscription();
        subscription.setId(IdUtil.fastSimpleUUID());
        subscription.setTenantId(request.getTenantId());
        subscription.setCallbackUrl(request.getCallbackUrl());
        subscription.setHmacSecret(hmacSecret);
        subscription.setEventKinds(CollectionUtils.isEmpty(request.getEventKinds())
                ? null : String.join(",", request.getEventKinds()));
        subscription.setDeviceIdFilter(CollectionUtils.isEmpty(request.getDeviceIdFilter())
                ? null : String.join(",", request.getDeviceIdFilter()));
        subscription.setConsecutiveFailureCount(0);
        subscription.setStatus("ACTIVE");
        subscription.setCreatedBy(operator);
        subscriptionMapper.insert(subscription);

        WebhookSubscriptionCreateResult result = new WebhookSubscriptionCreateResult();
        result.setId(subscription.getId());
        result.setHmacSecret(hmacSecret);
        return result;
    }

    @Override
    public void resume(String id) {
        WebhookSubscription subscription = subscriptionMapper.selectById(id);
        if (subscription == null) {
            throw new JeecgBootBizTipException(ERR_SUBSCRIPTION_NOT_FOUND);
        }
        if (!"PAUSED".equals(subscription.getStatus())) {
            throw new JeecgBootBizTipException("ERR_SUBSCRIPTION_NOT_PAUSED");
        }
        subscription.setStatus("ACTIVE");
        subscription.setConsecutiveFailureCount(0);
        subscriptionMapper.updateById(subscription);
    }

    @Override
    public void recordDispatchResult(String subscriptionId, boolean success) {
        WebhookSubscription subscription = subscriptionMapper.selectById(subscriptionId);
        if (subscription == null) {
            return;
        }
        if (success) {
            if (subscription.getConsecutiveFailureCount() != null && subscription.getConsecutiveFailureCount() > 0) {
                subscription.setConsecutiveFailureCount(0);
                subscriptionMapper.updateById(subscription);
            }
            return;
        }
        int failureCount = (subscription.getConsecutiveFailureCount() == null ? 0 : subscription.getConsecutiveFailureCount()) + 1;
        subscription.setConsecutiveFailureCount(failureCount);
        if (failureCount >= AUTO_PAUSE_THRESHOLD && "ACTIVE".equals(subscription.getStatus())) {
            subscription.setStatus("PAUSED");
            log.warn("[comm-hub] Webhook 订阅连续 {} 次投递失败，自动暂停 subscriptionId={} callbackUrl={}",
                    failureCount, subscriptionId, subscription.getCallbackUrl());
        }
        subscriptionMapper.updateById(subscription);
    }

    @Override
    public PageResult<WebhookSubscriptionDTO> list(WebhookSubscriptionListQuery query) {
        Page<WebhookSubscription> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<WebhookSubscription> pageResult = subscriptionMapper.selectPage(page, Wrappers.<WebhookSubscription>lambdaQuery()
                .eq(StringUtils.hasText(query.getTenantId()), WebhookSubscription::getTenantId, query.getTenantId())
                .eq(StringUtils.hasText(query.getStatus()), WebhookSubscription::getStatus, query.getStatus())
                .orderByDesc(WebhookSubscription::getCreatedAt));

        List<WebhookSubscriptionDTO> records = pageResult.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    public void disable(String id) {
        WebhookSubscription subscription = subscriptionMapper.selectById(id);
        if (subscription == null) {
            throw new JeecgBootBizTipException(ERR_SUBSCRIPTION_NOT_FOUND);
        }
        subscription.setStatus("DISABLED");
        subscriptionMapper.updateById(subscription);
    }

    private WebhookSubscriptionDTO toDTO(WebhookSubscription subscription) {
        WebhookSubscriptionDTO dto = new WebhookSubscriptionDTO();
        dto.setId(subscription.getId());
        dto.setTenantId(subscription.getTenantId());
        dto.setCallbackUrl(subscription.getCallbackUrl());
        dto.setEventKinds(StringUtils.hasText(subscription.getEventKinds())
                ? Arrays.asList(subscription.getEventKinds().split(",")) : Collections.emptyList());
        dto.setDeviceIdFilter(StringUtils.hasText(subscription.getDeviceIdFilter())
                ? Arrays.asList(subscription.getDeviceIdFilter().split(",")) : Collections.emptyList());
        dto.setConsecutiveFailureCount(subscription.getConsecutiveFailureCount());
        dto.setStatus(subscription.getStatus());
        dto.setCreatedBy(subscription.getCreatedBy());
        dto.setCreatedAt(subscription.getCreatedAt());
        return dto;
    }
}
