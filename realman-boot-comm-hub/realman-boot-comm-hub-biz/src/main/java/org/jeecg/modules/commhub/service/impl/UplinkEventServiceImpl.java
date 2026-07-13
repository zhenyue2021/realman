package org.jeecg.modules.commhub.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.commhub.entity.DeviceUplinkEventLog;
import org.jeecg.modules.commhub.entity.WebhookDeliveryTask;
import org.jeecg.modules.commhub.entity.WebhookSubscription;
import org.jeecg.modules.commhub.mapper.DeviceUplinkEventLogMapper;
import org.jeecg.modules.commhub.mapper.WebhookDeliveryTaskMapper;
import org.jeecg.modules.commhub.mapper.WebhookSubscriptionMapper;
import org.jeecg.modules.commhub.service.IUplinkEventService;
import org.jeecg.modules.commhub.vo.UplinkEventDTO;
import org.jeecg.modules.commhub.vo.UplinkEventQuery;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UplinkEventServiceImpl implements IUplinkEventService {

    private final DeviceUplinkEventLogMapper eventLogMapper;
    private final WebhookSubscriptionMapper subscriptionMapper;
    private final WebhookDeliveryTaskMapper deliveryTaskMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void ingest(DeviceUplinkEvent event) {
        DeviceUplinkEventLog entry = new DeviceUplinkEventLog();
        entry.setId(IdUtil.fastSimpleUUID());
        entry.setDeviceId(event.getDeviceId());
        entry.setDeviceCode(event.getDeviceCode());
        entry.setDeviceType(event.getDeviceType() == null ? null : event.getDeviceType().name());
        entry.setTenantId(event.getTenantId());
        entry.setEventKind(event.getEventKind() == null ? null : event.getEventKind().name());
        entry.setTransport(event.getTransport() == null ? null : event.getTransport().name());
        entry.setReportedAt(event.getReportedAt());
        try {
            entry.setPayload(objectMapper.writeValueAsString(event.getPayload()));
        } catch (Exception e) {
            log.warn("[comm-hub] 上行事件 payload 序列化失败 deviceCode={}: {}", event.getDeviceCode(), e.getMessage());
        }
        eventLogMapper.insert(entry);

        dispatchToSubscriptions(event, entry);
    }

    private void dispatchToSubscriptions(DeviceUplinkEvent event, DeviceUplinkEventLog entry) {
        if (!StringUtils.hasText(event.getTenantId())) {
            return;
        }
        List<WebhookSubscription> subscriptions = subscriptionMapper.selectList(Wrappers.<WebhookSubscription>lambdaQuery()
                .eq(WebhookSubscription::getTenantId, event.getTenantId())
                .eq(WebhookSubscription::getStatus, "ACTIVE"));
        if (subscriptions.isEmpty()) {
            return;
        }
        String eventKind = entry.getEventKind();
        LocalDateTime now = LocalDateTime.now();
        for (WebhookSubscription subscription : subscriptions) {
            if (matchesEventKind(subscription, eventKind) && matchesDeviceId(subscription, event.getDeviceId())) {
                WebhookDeliveryTask task = new WebhookDeliveryTask();
                task.setId(IdUtil.fastSimpleUUID());
                task.setEventLogId(entry.getId());
                task.setSubscriptionId(subscription.getId());
                task.setCallbackUrl(subscription.getCallbackUrl());
                task.setStatus("PENDING");
                task.setAttemptCount(0);
                task.setNextRetryAt(now);
                task.setCreatedAt(now);
                task.setUpdatedAt(now);
                deliveryTaskMapper.insert(task);
            }
        }
    }

    private static boolean matchesEventKind(WebhookSubscription subscription, String eventKind) {
        if (!StringUtils.hasText(subscription.getEventKinds())) {
            return true;
        }
        for (String kind : subscription.getEventKinds().split(",")) {
            if (kind.equals(eventKind)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesDeviceId(WebhookSubscription subscription, String deviceId) {
        if (!StringUtils.hasText(subscription.getDeviceIdFilter())) {
            return true;
        }
        if (!StringUtils.hasText(deviceId)) {
            return false;
        }
        for (String id : subscription.getDeviceIdFilter().split(",")) {
            if (id.equals(deviceId)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public PageResult<UplinkEventDTO> queryPage(UplinkEventQuery query) {
        Page<DeviceUplinkEventLog> page = new Page<>(query.getPageNo(), query.getPageSize());
        LambdaQueryWrapper<DeviceUplinkEventLog> wrapper = Wrappers.<DeviceUplinkEventLog>lambdaQuery()
                .eq(StringUtils.hasText(query.getTenantId()), DeviceUplinkEventLog::getTenantId, query.getTenantId())
                .eq(StringUtils.hasText(query.getDeviceId()), DeviceUplinkEventLog::getDeviceId, query.getDeviceId())
                .eq(StringUtils.hasText(query.getEventKind()), DeviceUplinkEventLog::getEventKind, query.getEventKind())
                .ge(query.getSince() != null, DeviceUplinkEventLog::getReportedAt, query.getSince());
        if (!CollectionUtils.isEmpty(query.getDeviceScope())) {
            wrapper.and(scope -> scope.in(DeviceUplinkEventLog::getDeviceId, query.getDeviceScope())
                    .or().in(DeviceUplinkEventLog::getDeviceCode, query.getDeviceScope()));
        }
        Page<DeviceUplinkEventLog> pageResult = eventLogMapper.selectPage(page, wrapper
                .orderByDesc(DeviceUplinkEventLog::getReportedAt));

        List<UplinkEventDTO> records = pageResult.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    private UplinkEventDTO toDTO(DeviceUplinkEventLog entry) {
        UplinkEventDTO dto = new UplinkEventDTO();
        dto.setId(entry.getId());
        dto.setDeviceId(entry.getDeviceId());
        dto.setDeviceCode(entry.getDeviceCode());
        dto.setDeviceType(entry.getDeviceType());
        dto.setTenantId(entry.getTenantId());
        dto.setEventKind(entry.getEventKind());
        dto.setTransport(entry.getTransport());
        dto.setReportedAt(entry.getReportedAt());
        if (StringUtils.hasText(entry.getPayload())) {
            try {
                dto.setPayload(objectMapper.readValue(entry.getPayload(), new TypeReference<Map<String, Object>>() {
                }));
            } catch (Exception e) {
                log.warn("[comm-hub] 上行事件 payload 反序列化失败 id={}", entry.getId());
                dto.setPayload(Collections.emptyMap());
            }
        }
        return dto;
    }
}
