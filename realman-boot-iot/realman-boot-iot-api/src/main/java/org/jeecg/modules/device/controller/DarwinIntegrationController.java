package org.jeecg.modules.device.controller;

import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.http.WorkOrderItemResult;
import org.jeecg.modules.device.datacollect.dto.mq.FileReportMsg;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.entity.workorder.WorkOrderAttachment;
import org.jeecg.modules.device.service.workorder.IWorkOrderAttachmentService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 数据处理模块（Darwin）→ 我方的同步 HTTP 直连入口，替代 V2 主设计文档第六章"链路④工单
 * 下发"（原 {@code WorkOrderCreateConsumer}）与文件上报结果回传（原 {@code FileReportConsumer}，
 * 后者不在设计文档原始四条链路枚举内，是排查落地时发现的第五条既有链路，一并给出 HTTP 对应端点）。
 *
 * <p>不依赖 {@code darwin.integration.http-enabled}——出站三条链路（OSS 授权/文件地址/设备
 * 状态）走哪条通道由我方 {@code DarwinHttpClient} 的开关决定；入站两条链路走哪条通道
 * （RocketMQ Consumer 还是本控制器）取决于达尔文平台侧的调用方式，我方只需始终暴露好本
 * 端点，不需要额外开关。
 *
 * <p>相对旧 RocketMQ Consumer 的改进：{@code WorkOrderCreateConsumer} 原实现里任意一项
 * 处理异常就返回 {@code ConsumeResult.FAILURE} 导致整批消息重新投递（包括已成功处理的项），
 * 本端点按单项 try/catch 并返回逐项结果，调用方可精确知道哪些工单失败、原因是什么。
 *
 * <p><b>重要限制</b>：达尔文平台真实回调契约（路径/字段/鉴权方式）本仓库无法访问，以下路径
 * 直接复用现有 RocketMQ 消息体结构，按假设契约实现，正式对接前需要与达尔文平台侧核实。
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class DarwinIntegrationController {

    private final IWorkOrderService workOrderService;
    private final IWorkOrderAttachmentService attachmentService;
    private final StringRedisTemplate redisTemplate;

    @IgnoreAuth
    @PostMapping("/task/data-collect-task")
    @Operation(summary = "达尔文工单下发（替代 MQ_TOPIC_WORK_ORDER_IN 消费者），按单项返回结果")
    public ApiResult<List<WorkOrderItemResult>> createDataCollectTask(@Valid @RequestBody WorkOrderCreateMsg request) {
        List<WorkOrderItemResult> results = new ArrayList<>();
        if (request.getData() == null || request.getData().isEmpty()) {
            return ApiResult.ok(results, "data 为空，无工单需要处理");
        }
        String tenant = request.getTenant() != null ? request.getTenant() : "";
        for (WorkOrderCreateMsg.WorkOrderItem item : request.getData()) {
            if (item.getId() == null || item.getId().isBlank()) {
                log.warn("[DataCollect][HTTP] 工单项缺少 id，跳过");
                continue;
            }
            try {
                if ("true".equalsIgnoreCase(item.getDeleted())) {
                    workOrderService.deleteWorkOrderFromDarwin(item.getId());
                } else {
                    workOrderService.upsertWorkOrderFromDarwin(
                            item.getId(), tenant, item, request.getTraceId(), request.getDeviceCode());
                }
                results.add(WorkOrderItemResult.ok(item.getId()));
            } catch (Exception e) {
                log.error("[DataCollect][HTTP] 工单处理失败 workOrderId={}", item.getId(), e);
                results.add(WorkOrderItemResult.fail(item.getId(), e.getMessage()));
            }
        }
        return ApiResult.ok(results);
    }

    @IgnoreAuth
    @PostMapping("/data-processing/collected-file")
    @Operation(summary = "达尔文文件上报结果回传（替代 MQ_TOPIC_FILE_REPORT_IN 消费者）")
    public ApiResult<Void> reportCollectedFile(@Valid @RequestBody FileReportMsg request) {
        if (request.getDarwinFileId() == null || request.getDarwinFileId().isBlank()) {
            return ApiResult.fail("缺少 darwinFileId");
        }
        String dedupKey = DataCollectConstant.REDIS_DARWIN_FILE_DEDUP + request.getDarwinFileId();
        Boolean isNew = redisTemplate.opsForValue().setIfAbsent(dedupKey, "1", 24, TimeUnit.HOURS);
        if (Boolean.FALSE.equals(isNew)) {
            log.info("[DataCollect][HTTP] 文件上报已处理，跳过 darwinFileId={}", request.getDarwinFileId());
            return ApiResult.ok(null, "已处理，重复请求忽略");
        }

        if (request.getWorkOrderId() != null && !request.getWorkOrderId().isBlank()) {
            WorkOrderAttachment attachment = new WorkOrderAttachment();
            attachment.setWorkOrderId(request.getWorkOrderId());
            attachment.setFileName(request.getFileName());
            attachment.setFileUrl(request.getFileUrl());
            attachment.setDescription("达尔文平台上传 darwinFileId=" + request.getDarwinFileId());
            attachment.setCreateTime(LocalDateTime.now());
            attachmentService.addAttachments(request.getWorkOrderId(), "darwin", List.of(attachment));
        } else {
            log.warn("[DataCollect][HTTP] 文件上报无 workOrderId，跳过附件关联 darwinFileId={}", request.getDarwinFileId());
        }
        log.info("[DataCollect][HTTP] 文件上报处理成功 darwinFileId={} workOrderId={}",
                request.getDarwinFileId(), request.getWorkOrderId());
        return ApiResult.ok(null);
    }
}
