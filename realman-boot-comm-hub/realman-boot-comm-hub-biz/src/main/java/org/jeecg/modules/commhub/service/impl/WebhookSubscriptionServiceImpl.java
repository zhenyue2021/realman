package org.jeecg.modules.commhub.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
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

@Service
@RequiredArgsConstructor
public class WebhookSubscriptionServiceImpl implements IWebhookSubscriptionService {

    private static final String ERR_SUBSCRIPTION_NOT_FOUND = "ERR_SUBSCRIPTION_NOT_FOUND";

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
        subscription.setStatus("ACTIVE");
        subscription.setCreatedBy(operator);
        subscriptionMapper.insert(subscription);

        WebhookSubscriptionCreateResult result = new WebhookSubscriptionCreateResult();
        result.setId(subscription.getId());
        result.setHmacSecret(hmacSecret);
        return result;
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
        dto.setStatus(subscription.getStatus());
        dto.setCreatedBy(subscription.getCreatedBy());
        dto.setCreatedAt(subscription.getCreatedAt());
        return dto;
    }
}
