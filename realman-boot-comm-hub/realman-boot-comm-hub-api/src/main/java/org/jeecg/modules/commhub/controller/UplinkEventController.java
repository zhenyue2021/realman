package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.commhub.service.IUplinkEventService;
import org.jeecg.modules.commhub.vo.UplinkEventDTO;
import org.jeecg.modules.commhub.vo.UplinkEventQuery;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 上行事件轮询兜底通道：第三方不便接收 Webhook 时，定时轮询本接口获取增量事件，
 * 见设备通信中台详细设计 4.3.2。
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "上行事件查询", description = "Webhook 推送的轮询兜底通道")
public class UplinkEventController {

    private final IUplinkEventService uplinkEventService;

    @GetMapping("/api/v1/devices/uplink-events")
    @Operation(summary = "上行事件轮询查询")
    public Result<PageResult<UplinkEventDTO>> queryPage(UplinkEventQuery query) {
        return Result.ok(uplinkEventService.queryPage(query));
    }
}
