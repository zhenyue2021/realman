package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.WorkOrderConstant;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Darwin 工单同步：MQ 创建/更新/删除、机器人绑定、WebSocket 列表推送。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkOrderDarwinSyncService extends ServiceImpl<WorkOrderMapper, WorkOrder> {

    private static final DateTimeFormatter DARWIN_DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.[SSSSSSSSS][SSSSSS][SSS]]");

    private final ObjectMapper objectMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IotDeviceMapper iotDeviceMapper;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final Environment environment;

    @Autowired(required = false)
    private SysAuthFeignClient sysAuthFeignClient;

    @Transactional(rollbackFor = Exception.class)
    public WorkOrder upsertWorkOrderFromDarwin(String workOrderId, String tenant,
                                               WorkOrderCreateMsg.WorkOrderItem item,
                                               String traceId, String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            log.warn("[Darwin] deviceCode 为空，忽略工单 workOrderId={}", workOrderId);
            return null;
        }
        IotDevice robot = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        if (robot == null) {
            log.warn("[Darwin] 设备不存在，忽略工单 workOrderId={} deviceCode={}", workOrderId, deviceCode);
            return null;
        }

        WorkOrder existing = this.getById(workOrderId);
        WorkOrder result;
        if (existing != null) {
            result = updateFromDarwin(existing, tenant, item);
            if (!WorkOrderConstant.ORDER_STATUS.STARTED.equals(existing.getStatus())
                    && !WorkOrderConstant.ORDER_STATUS.SUBMITTED.equals(existing.getStatus())
                    && !WorkOrderConstant.ORDER_STATUS.COMPLETED.equals(existing.getStatus())) {
                bindRobotDevice(workOrderId, deviceCode);
            }
        } else {
            result = createFromDarwin(workOrderId, tenant, item);
            bindRobotDevice(workOrderId, deviceCode);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrderFromDarwin(String workOrderId) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            log.warn("[Darwin] 工单不存在，跳过删除 workOrderId={}", workOrderId);
            return;
        }
        String status = order.getStatus();
        if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.SUBMITTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.COMPLETED.equals(status)) {
            log.info("[Darwin] 工单状态为 {}，跳过删除 workOrderId={}", status, workOrderId);
            return;
        }
        this.removeById(workOrderId);
        log.info("[Darwin] 工单删除完成 workOrderId={}", workOrderId);
    }

    public void pushDarwinWorkOrdersForDevice(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            return;
        }
        IotDevice device = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null || DeviceConstant.DeviceTypeInteger.ROBOT != device.getDeviceType()) {
            return;
        }

        List<WorkOrderDevice> robotBindings = workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getDeviceCode, deviceCode)
                        .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.ROBOT));
        if (robotBindings.isEmpty()) {
            return;
        }

        List<String> orderIds = robotBindings.stream()
                .map(WorkOrderDevice::getWorkOrderId).distinct().toList();

        List<WorkOrder> orders = this.list(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getId, orderIds)
                .in(WorkOrder::getStatus, WorkOrderConstant.ORDER_STATUS.PENDING, WorkOrderConstant.ORDER_STATUS.STARTED)
                .eq(WorkOrder::getDelFlag, 0)
                .orderByAsc(WorkOrder::getPlanStartTime));
        if (orders.isEmpty()) {
            return;
        }

        try {
            String listJson = objectMapper.writeValueAsString(orders);
            deviceWebSocketServer.pushDarwinWorkOrderList(deviceCode, listJson);
        } catch (Exception e) {
            log.warn("[Darwin] 推送工单列表失败 deviceCode={}", deviceCode, e);
            return;
        }
        log.info("[Darwin] 已批量推送工单 {} 条至 deviceCode={}", orders.size(), deviceCode);
    }

    private WorkOrder createFromDarwin(String workOrderId, String tenant,
                                       WorkOrderCreateMsg.WorkOrderItem item) {
        WorkOrder order = new WorkOrder();
        order.setId(workOrderId);
        applyDarwinFields(order, tenant, item);
        order.setStatus(WorkOrderConstant.ORDER_STATUS.PENDING);
        order.setSource(2);
        order.setCreateBy("darwin");
        order.setUpdateBy("darwin");
        storeDarwinDataSource(order, item);
        this.save(order);
        log.info("[Darwin] 工单创建完成 workOrderId={}", workOrderId);
        return order;
    }

    private WorkOrder updateFromDarwin(WorkOrder order, String tenant,
                                       WorkOrderCreateMsg.WorkOrderItem item) {
        String status = order.getStatus();
        if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.SUBMITTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.COMPLETED.equals(status)) {
            log.info("[Darwin] 工单状态为 {}，跳过更新 workOrderId={}", status, order.getId());
            return order;
        }
        applyDarwinFields(order, tenant, item);
        if (WorkOrderConstant.ORDER_STATUS.TIMEOUT.equals(status)
                || WorkOrderConstant.ORDER_STATUS.CLOSED.equals(status)) {
            order.setStatus(WorkOrderConstant.ORDER_STATUS.PENDING);
        }
        order.setUpdateBy("darwin");
        storeDarwinDataSource(order, item);
        this.updateById(order);
        log.info("[Darwin] 工单更新完成 workOrderId={} status={} → {}", order.getId(), status, order.getStatus());
        return order;
    }

    private void bindRobotDevice(String workOrderId, String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            log.warn("[Darwin] deviceCode 为空，跳过设备绑定 workOrderId={}", workOrderId);
            return;
        }
        IotDevice robot = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        if (robot == null) {
            log.warn("[Darwin] 机器人设备不存在，跳过绑定 deviceCode={} workOrderId={}", deviceCode, workOrderId);
            return;
        }
        workOrderDeviceMapper.delete(new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.ROBOT));
        WorkOrderDevice wd = new WorkOrderDevice();
        wd.setWorkOrderId(workOrderId);
        wd.setDeviceCode(robot.getDeviceCode());
        wd.setDeviceId(robot.getId());
        wd.setDeviceName(robot.getDeviceName());
        wd.setDeviceType(DeviceConstant.DeviceType.ROBOT);
        wd.setCreateTime(LocalDateTime.now());
        workOrderDeviceMapper.insert(wd);
        log.info("[Darwin] 机器人设备绑定成功 workOrderId={} deviceCode={}", workOrderId, deviceCode);
    }

    private void applyDarwinFields(WorkOrder order, String tenant,
                                   WorkOrderCreateMsg.WorkOrderItem item) {
        WorkOrderCreateMsg.CollectionPlan plan = item.getCollectionPlan();
        WorkOrderCreateMsg.CollectionItem ci = item.getCollectionItem();
        order.setPlanStartTime(parseDateTime(plan != null ? plan.getBeginAt() : null));
        order.setPlanEndTime(parseDateTime(plan != null ? plan.getEndAt() : null));
        order.setRemark(plan != null ? plan.getName() : null);
        order.setTaskDesc(formatTaskDesc(ci));
        order.setQuotaTotal(parseQuota(item.getQuotaValue()));
        order.setTenantId(tenant);
        order.setAgentId(tenant);
        order.setAgentName(resolveTenantName(tenant));
        order.setDepartmentId(environment.getProperty("darwin.integration.default-department-id", ""));
        order.setDepartmentName(environment.getProperty("darwin.integration.default-department-name", ""));
        if (ci != null) {
            order.setTaskName(ci.getName());
            order.setCollectionItemNameEn(ci.getNameEn());
        }
        if (item.getLevel1Scene() != null) {
            order.setLevel1SceneNameEn(item.getLevel1Scene().getNameEn());
        }
        if (item.getLevel2Scene() != null) {
            order.setLevel2SceneNameEn(item.getLevel2Scene().getNameEn());
        }
    }

    private void storeDarwinDataSource(WorkOrder order, WorkOrderCreateMsg.WorkOrderItem item) {
        try {
            order.setDarwinDataSource(objectMapper.writeValueAsString(item));
        } catch (Exception e) {
            log.warn("[Darwin] darwinDataSource 序列化失败 workOrderId={}", order.getId(), e);
        }
    }

    private static String formatTaskDesc(WorkOrderCreateMsg.CollectionItem item) {
        if (item == null || item.getActions() == null || item.getActions().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<WorkOrderCreateMsg.Action> actions = item.getActions();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) {
                sb.append("teleop");
            }
            sb.append(i + 1).append(".").append(actions.get(i).getName());
        }
        return sb.toString();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, DARWIN_DT_FMT);
        } catch (Exception e) {
            log.warn("[Darwin] 日期时间解析失败 value={}", value);
            return null;
        }
    }

    private String resolveTenantName(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || sysAuthFeignClient == null) {
            return tenantId;
        }
        try {
            String name = sysAuthFeignClient.getTenantNameById(tenantId);
            return (name != null && !name.isBlank()) ? name : tenantId;
        } catch (Exception e) {
            log.warn("[Darwin] 查询租户名称失败 tenantId={}", tenantId, e);
            return tenantId;
        }
    }

    private Integer parseQuota(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[Darwin] quotaValue 解析失败 value={}", value);
            return null;
        }
    }
}
