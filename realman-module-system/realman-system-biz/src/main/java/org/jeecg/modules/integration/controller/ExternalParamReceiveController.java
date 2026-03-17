package org.jeecg.modules.integration.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.integration.dto.ExternalParamReceiveDTO;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/integration/external")
@Tag(name = "外部系统参数接收")
public class ExternalParamReceiveController {

    @PostMapping("/receive")
    @Operation(summary = "外部系统传参接收（POST JSON）")
    public Result<Map<String, Object>> receive(@Valid @RequestBody ExternalParamReceiveDTO dto) {
        // 这里先做“接收 + 回执”。后续如果你要落库/入MQ/做幂等，我再帮你补 service 层。
        log.info("External params received: sourceSystem={}, requestId={}, bizType={}, paramsKeys={}",
                dto.getSourceSystem(), dto.getRequestId(), dto.getBizType(),
                dto.getParams() == null ? null : dto.getParams().keySet());

        Map<String, Object> ack = new HashMap<>();
        ack.put("received", true);
        return Result.ok(ack);
    }
}

