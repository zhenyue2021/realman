package org.jeecg.modules.device.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.WorkOrderDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.IMasterOperationRecordService;
import org.jeecg.modules.device.service.IWorkOrderSchedulerService;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工单定时任务业务逻辑实现
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderSchedulerServiceImpl implements IWorkOrderSchedulerService {

    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderComplianceConfigMapper configMapper;
    private final IMasterOperationRecordService operationRecordService;
    private final IWorkOrderService workOrderService;
    private final IWorkOrderComplianceConfigService workOrderConfigService;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final StringRedisTemplate redisTemplate;
    private final DeviceWebSocketServer webSocketServer;
    private final ObjectMapper objectMapper;

    @Override
    public void timeoutAlert() {
        LocalDateTime now = LocalDateTime.now();

        // 只筛选未来1小时内将结束的 PENDING/STARTED 工单，避免全表扫描
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .in(WorkOrder::getStatus, "PENDING", "STARTED")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .ge(WorkOrder::getPlanEndTime, now)
                        .le(WorkOrder::getPlanEndTime, now.plusHours(1))
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int alerted = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            // 合规配置不存在或未启用超时提醒，跳过
            if (cfg == null || cfg.getTimeoutAlertEnabled() == null || cfg.getTimeoutAlertEnabled() != 1) {
                continue;
            }
            // 解析提醒偏移时间（HH:mm:ss），默认30分钟
            int seconds = parseHmsSeconds(cfg.getTimeoutAlertOffset(), 1800);
            long diff = Duration.between(now, o.getPlanEndTime()).getSeconds();
            if (diff <= 0 || diff > seconds) {
                continue;
            }
            alerted++;
            // 记录日志；实际可在此接入消息推送至主控端
            log.info("[WorkOrder-TimeoutAlert] 工单即将超时, id={}, endTime={}, remainSeconds={}",
                    o.getId(), o.getPlanEndTime(), diff);
        }
        if (alerted > 0) {
            log.info("[WorkOrder-TimeoutAlert] 本次共提醒工单 {} 条", alerted);
        }
    }

    @Override
    public void timeoutMark() {
        LocalDateTime now = LocalDateTime.now();

        // 查询计划结束时间已过、仍处于 PENDING/STARTED 状态的工单
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .in(WorkOrder::getStatus, "PENDING", "STARTED")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .le(WorkOrder::getPlanEndTime, now)
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int updated = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            // 未启用任务限制，不做超时标记
            if (cfg == null || cfg.getTaskLimitEnabled() == null || cfg.getTaskLimitEnabled() != 1) {
                continue;
            }
            // 已开启超时提交（overtimeEnabled=1），由提交接口自行校验，此处不标记 TIMEOUT
            if (cfg.getOvertimeEnabled() != null && cfg.getOvertimeEnabled() == 1) {
                continue;
            }
            o.setStatus("TIMEOUT");
            workOrderMapper.updateById(o);
            // 同步结束操作记录，结束时间取计划结束时间
            if (o.getPlanEndTime() != null) {
                operationRecordService.finishByWorkOrder(o.getId(), o.getPlanEndTime());
            }
            updated++;
        }
        if (updated > 0) {
            log.warn("[WorkOrder-TimeoutMark] 本次标记超时工单 {} 条", updated);
        }
    }

    @Override
    public void autoClose() {
        LocalDateTime now = LocalDateTime.now();

        // 查询 TIMEOUT 状态且超时原因为空的工单（尚未填写原因）
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getStatus, "TIMEOUT")
                        .isNotNull(WorkOrder::getPlanEndTime)
                        .isNull(WorkOrder::getTimeoutReason)
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }

        Map<String, WorkOrderComplianceConfig> configMap = loadConfigs(candidates);
        int closed = 0;
        for (WorkOrder o : candidates) {
            WorkOrderComplianceConfig cfg = configMap.get(o.getComplianceId());
            // 未启用自动关闭，跳过
            if (cfg == null || cfg.getAutoCloseEnabled() == null || cfg.getAutoCloseEnabled() != 1) {
                continue;
            }
            // 解析自动关闭偏移时间（HH:mm:ss），偏移为0则不自动关闭
            int seconds = parseHmsSeconds(cfg.getAutoCloseOffset(), 0);
            if (seconds <= 0) {
                continue;
            }
            // 判断当前时间是否已超过"计划结束时间 + 自动关闭偏移"
            LocalDateTime threshold = o.getPlanEndTime().plusSeconds(seconds);
            if (now.isBefore(threshold)) {
                continue;
            }
            // 系统自动关闭，填充关闭信息
            o.setStatus("CLOSED");
            o.setTimeoutReasonSource("SYSTEM");
            o.setTimeoutReason("用户原因");
            o.setCloseTime(now);
            o.setCloseBy("SYSTEM");
            workOrderMapper.updateById(o);
            closed++;
        }
        if (closed > 0) {
            log.warn("[WorkOrder-AutoClose] 本次自动关闭超时未填原因工单 {} 条", closed);
        }
    }

    @Override
    public void startTimePush() {
        LocalDateTime now = LocalDateTime.now();

        // 查询已到开始时间、仍处于 PENDING 状态的工单（仅最近24小时，防止翻查全量历史）
        // 终止条件：工单变为 STARTED 后不再出现在此查询中，推送自然停止
        List<WorkOrder> candidates = workOrderMapper.selectList(
                new LambdaQueryWrapper<WorkOrder>()
                        .eq(WorkOrder::getStatus, "PENDING")
                        .isNotNull(WorkOrder::getPlanStartTime)
                        .le(WorkOrder::getPlanStartTime, now)                // 计划开始时间已到
                        .ge(WorkOrder::getPlanStartTime, now.minusHours(24)) // 仅最近24小时内
                        .eq(WorkOrder::getDelFlag, 0)
        );
        if (candidates.isEmpty()) {
            return;
        }

        // 批量加载工单关联的主控设备编码（workOrderId -> controllerCode）
        Map<String, String> controllerCodeByOrderId = loadControllerDeviceCodes(candidates);
        if (controllerCodeByOrderId.isEmpty()) {
            return;
        }

        // 每次定时任务触发都向主控端推送，直到工单状态变为 STARTED 为止；
        // 主控设备离线时跳过，等其上线后下次触发时再推送
        int pushed = 0;
        int skippedNoDevice = 0;
        int skippedOffline = 0;
        for (WorkOrder o : candidates) {
            String controllerCode = controllerCodeByOrderId.get(o.getId());
            if (controllerCode == null || controllerCode.isBlank()) {
                skippedNoDevice++;
                continue;
            }
            // 校验主控设备是否在线（Redis Set iot:device:online 中存在即为在线）
            boolean online = Boolean.TRUE.equals(
                    redisTemplate.opsForSet().isMember(DeviceConstant.RedisKey.DEVICE_ONLINE_SET, controllerCode));
            if (!online) {
                skippedOffline++;
                log.debug("[WorkOrder-StartPush] 主控不在线，跳过推送 orderId={} controllerCode={}",
                        o.getId(), controllerCode);
                continue;
            }
            try {
                String json = objectMapper.writeValueAsString(o);
                webSocketServer.pushPendingWorkOrder(controllerCode, json);
                pushed++;
                log.debug("[WorkOrder-StartPush] pushed orderId={} controllerCode={} planStartTime={}",
                        o.getId(), controllerCode, o.getPlanStartTime());
            } catch (JsonProcessingException e) {
                log.warn("[WorkOrder-StartPush] serialize failed, orderId={}", o.getId(), e);
            } catch (Exception e) {
                log.warn("[WorkOrder-StartPush] push failed, orderId={} controllerCode={}", o.getId(), controllerCode, e);
            }
        }
        if (pushed > 0 || skippedOffline > 0) {
            log.info("[WorkOrder-StartPush] 本次推送 {} 条，主控离线跳过 {} 条，无主控跳过 {} 条",
                    pushed, skippedOffline, skippedNoDevice);
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 批量加载工单对应的主控设备编码
     * <p>优先取 actualDeviceCode（实际绑定编码），降级取 deviceCode（原始编码）。
     * 同一工单有多条主控记录时取第一条（正常业务下一张工单只绑定一台主控）。
     *
     * @param orders 候选工单列表
     * @return Map&lt;workOrderId, controllerCode&gt;
     */
    private Map<String, String> loadControllerDeviceCodes(List<WorkOrder> orders) {
        List<String> orderIds = orders.stream()
                .map(WorkOrder::getId)
                .filter(id -> id != null && !id.isBlank())
                .distinct()
                .toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        List<WorkOrderDevice> devices = workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .in(WorkOrderDevice::getWorkOrderId, orderIds)
                        .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER)
        );
        if (devices == null || devices.isEmpty()) {
            return Map.of();
        }
        return devices.stream()
                .filter(d -> d.getWorkOrderId() != null && !d.getWorkOrderId().isBlank())
                .collect(Collectors.toMap(
                        WorkOrderDevice::getWorkOrderId,
                        d -> (d.getActualDeviceCode() != null && !d.getActualDeviceCode().isBlank())
                                ? d.getActualDeviceCode()
                                : d.getDeviceCode(),
                        (a, b) -> a  // 同一工单多条记录取第一条
                ));
    }

    /**
     * 批量加载工单关联的合规配置，以 complianceId 为 key 返回 Map。
     *
     * @param orders 候选工单列表
     * @return Map&lt;complianceId, WorkOrderComplianceConfig&gt;
     */
    private Map<String, WorkOrderComplianceConfig> loadConfigs(List<WorkOrder> orders) {
        List<String> ids = orders.stream()
                .map(WorkOrder::getComplianceId)
                .filter(id -> id != null && !id.isEmpty())
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<WorkOrderComplianceConfig> cfgs = configMapper.selectList(
                new LambdaQueryWrapper<WorkOrderComplianceConfig>()
                        .in(WorkOrderComplianceConfig::getId, ids)
                        .eq(WorkOrderComplianceConfig::getDelFlag, 0)
        );
        return cfgs.stream().collect(Collectors.toMap(WorkOrderComplianceConfig::getId, c -> c));
    }


    @Override
    public void pushStartedWorkOrders() {
        LocalDateTime now = LocalDateTime.now();
        // 查询所有已开启且未超时的工单
        List<WorkOrder> startedOrders = workOrderService.list(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getStatus, "STARTED")
                .eq(WorkOrder::getDelFlag, 0)
                .gt(WorkOrder::getPlanEndTime, now));
        if (startedOrders.isEmpty()) {
            return;
        }
        for (WorkOrder order : startedOrders) {
            WorkOrderDevice master = workOrderService.findMasterDevice(order.getId());
            if (master == null || master.getDeviceCode() == null) {
                log.warn("[pushStartedWorkOrders] 工单无主控设备，跳过: workOrderId={}", order.getId());
                continue;
            }
            // 获取工单合规配置
            WorkOrderComplianceConfig complianceConfig = workOrderConfigService.getById(order.getComplianceId());
            WorkOrderDTO workOrderDTO = new WorkOrderDTO();
            try {
                BeanUtil.copyProperties(order, workOrderDTO);
                workOrderDTO.setWorkOrderComplianceConfig(complianceConfig);
                String workOrderJson = objectMapper.writeValueAsString(workOrderDTO);
                webSocketServer.pushStartedWorkOrder(master.getDeviceCode(), workOrderJson);
            } catch (Exception e) {
                log.warn("[pushStartedWorkOrders] WebSocket 推送失败: workOrderId={}, err={}", order.getId(), e.getMessage());
            }
        }
        log.debug("[pushStartedWorkOrders] 推送进行中工单 {} 条", startedOrders.size());
    }

    /**
     * 将 "HH:mm:ss" 格式字符串解析为总秒数。
     * <p>格式不合法或为空时返回 defaultSeconds。
     *
     * @param hms            时间字符串，如 "00:30:00"
     * @param defaultSeconds 解析失败时的默认秒数
     * @return 总秒数
     */
    private static int parseHmsSeconds(String hms, int defaultSeconds) {
        if (hms == null || hms.isBlank()) {
            return defaultSeconds;
        }
        String[] parts = hms.trim().split(":");
        if (parts.length != 3) {
            return defaultSeconds;
        }
        try {
            int h = Integer.parseInt(parts[0].trim());
            int m = Integer.parseInt(parts[1].trim());
            int s = Integer.parseInt(parts[2].trim());
            if (h < 0 || m < 0 || s < 0) {
                return defaultSeconds;
            }
            return h * 3600 + m * 60 + s;
        } catch (Exception e) {
            return defaultSeconds;
        }
    }
}
