package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.dto.UplinkEventPollQuery;
import org.jeecg.modules.commhub.contract.event.DeviceUplinkEvent;
import org.jeecg.modules.commhub.contract.event.EventKind;
import org.jeecg.modules.commhub.contract.event.Transport;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.commhub.service.IApiKeyService;
import org.jeecg.modules.commhub.service.IUplinkEventService;
import org.jeecg.modules.commhub.vo.ApiKeyAuthContext;
import org.jeecg.modules.commhub.vo.UplinkEventDTO;
import org.jeecg.modules.commhub.vo.UplinkEventQuery;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 上行事件轮询兜底通道：第三方不便接收 Webhook 时，通过对外接口携带
 * {@code X-Api-Key} 获取其租户与设备授权范围内的增量事件，见设备通信中台详细设计 4.3.2。
 * {@link #pollInternal} 是同一份落库数据的内部 Feign 版本，供 OTA 等业务服务消费，
 * 不复用对外 API Key 鉴权路径，见 OTA 平台详细设计第二章协议映射表。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "上行事件查询", description = "Webhook 推送的轮询兜底通道 + 内部消费入口")
public class UplinkEventController {

    private static final String HEADER_API_KEY = "X-Api-Key";

    private final IUplinkEventService uplinkEventService;
    private final IApiKeyService apiKeyService;

    @GetMapping("/api/v1/devices/uplink-events")
    @Operation(summary = "上行事件轮询查询（第三方，需 X-Api-Key，按租户和设备范围过滤）")
    public Result<PageResult<UplinkEventDTO>> queryPage(UplinkEventQuery query,
                                                         @RequestHeader(HEADER_API_KEY) String apiKey) {
        ApiKeyAuthContext authContext = apiKeyService.authenticatePolling(apiKey, query.getDeviceId());
        query.setTenantId(authContext.getTenantId());
        query.setDeviceScope(authContext.getDeviceScope());
        return Result.ok(uplinkEventService.queryPage(query));
    }

    @Hidden
    @GetMapping("/internal/comm-hub/uplink-events")
    @Operation(summary = "上行事件轮询查询（内部 Feign）")
    public Result<List<DeviceUplinkEvent>> pollInternal(UplinkEventPollQuery query) {
        UplinkEventQuery internalQuery = new UplinkEventQuery();
        internalQuery.setEventKind(query.getEventKind());
        internalQuery.setSince(query.getSince());
        internalQuery.setPageNo(1);
        internalQuery.setPageSize(query.getLimit() == null ? 200 : query.getLimit());

        List<DeviceUplinkEvent> events = uplinkEventService.queryPage(internalQuery).getRecords().stream()
                .map(this::toContractEvent).collect(Collectors.toList());
        return Result.ok(events);
    }

    private DeviceUplinkEvent toContractEvent(UplinkEventDTO dto) {
        DeviceUplinkEvent event = new DeviceUplinkEvent();
        event.setDeviceId(dto.getDeviceId());
        event.setDeviceCode(dto.getDeviceCode());
        event.setDeviceType(StringUtils.hasText(dto.getDeviceType()) ? DeviceType.valueOf(dto.getDeviceType()) : null);
        event.setTenantId(dto.getTenantId());
        event.setEventKind(StringUtils.hasText(dto.getEventKind()) ? EventKind.valueOf(dto.getEventKind()) : null);
        event.setTransport(StringUtils.hasText(dto.getTransport()) ? Transport.valueOf(dto.getTransport()) : null);
        event.setPayload(dto.getPayload());
        event.setReportedAt(dto.getReportedAt());
        return event;
    }
}
