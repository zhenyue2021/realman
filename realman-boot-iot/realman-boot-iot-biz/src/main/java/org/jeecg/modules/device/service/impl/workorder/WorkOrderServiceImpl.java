package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.WorkOrderConstant;
import org.jeecg.modules.device.datacollect.dto.mq.WorkOrderCreateMsg;
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.datacollect.dto.mqtt.StartCollectCmd;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.datacollect.dto.mqtt.StopCollectCmd;
import org.jeecg.modules.device.datacollect.config.DataCollectIntegrationProperties;
import org.jeecg.modules.device.datacollect.service.DataCollectCommandService;
import org.jeecg.modules.device.mapper.workorder.WorkOrderMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.service.workorder.IWorkOrderStateMachineService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderServiceImpl extends ServiceImpl<WorkOrderMapper, WorkOrder>
        implements IWorkOrderService {

    private final ObjectMapper objectMapper;
    private final WorkOrderMapper workOrderMapper;
    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final WorkOrderComplianceConfigMapper complianceConfigMapper;
    private final DeviceWebSocketServer deviceWebSocketServer;
    private final IWorkOrderStateMachineService workOrderStateMachine;
    private final IotDeviceMapper iotDeviceMapper;
    private final DataCollectIntegrationProperties darwinProps;
    /** darwin.integration.enabled=false 时 Bean 不存在，启动时注入 null，调用前做空判断 */
    @Autowired(required = false)
    private DataCollectCommandService dataCollectCommandService;
    @Autowired(required = false)
    private SysAuthFeignClient sysAuthFeignClient;

    @Override
    public IPage<WorkOrder> pageWorkOrders(Page<WorkOrder> page, String agentId, String status) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrder::getAgentId, agentId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        wrapper.eq(WorkOrder::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public List<WorkOrder> listForExport(String agentId, String status) {
        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrder::getAgentId, agentId);
        }
        if (status != null && !status.isEmpty()) {
            wrapper.eq(WorkOrder::getStatus, status);
        }
        wrapper.eq(WorkOrder::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrder::getCreateTime);
        return this.list(wrapper);
    }

    @Override
    public List<WorkOrder> listPendingForController(String controllerCode) {
        return listPendingForControllerAndDepartments(controllerCode, null);
    }

    @Override
    public List<WorkOrder> listPendingForControllerAndDepartments(String controllerCode, List<String> departmentIds) {
        if (controllerCode == null || controllerCode.isEmpty()) {
            return List.of();
        }
        LambdaQueryWrapper<WorkOrderDevice> deviceWrapper = new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER)
                .eq(WorkOrderDevice::getDeviceCode, controllerCode);
        List<WorkOrderDevice> binds = workOrderDeviceMapper.selectList(deviceWrapper);
        if (binds.isEmpty()) {
            return List.of();
        }
        List<String> orderIds = binds.stream()
                .map(WorkOrderDevice::getWorkOrderId)
                .distinct()
                .toList();

        LambdaQueryWrapper<WorkOrder> wrapper = new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getId, orderIds)
                .in(WorkOrder::getStatus, WorkOrderConstant.ORDER_STATUS.PENDING, WorkOrderConstant.ORDER_STATUS.STARTED)
                .eq(WorkOrder::getDelFlag, 0)
                .orderByAsc(WorkOrder::getPlanStartTime);
        if (departmentIds != null && !departmentIds.isEmpty()) {
            wrapper.in(WorkOrder::getDepartmentId, departmentIds);
        }
        return this.baseMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void bindDevices(String workOrderId, List<WorkOrderDevice> devices) {
        workOrderDeviceMapper.delete(new LambdaQueryWrapper<WorkOrderDevice>()
                .eq(WorkOrderDevice::getWorkOrderId, workOrderId));
        if (devices == null || devices.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        for (WorkOrderDevice d : devices) {
            d.setId(null);
            d.setWorkOrderId(workOrderId);
            d.setCreateTime(now);
            workOrderDeviceMapper.insert(d);
        }
    }

    @Override
    public List<WorkOrderDevice> findDevices(String workOrderId) {
        return workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId));
    }

    @Override
    public WorkOrderDevice findMasterDevice(String workOrderId) {
        return workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>().eq(WorkOrderDevice::getWorkOrderId, workOrderId).eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.CONTROLLER).last("LIMIT 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startWorkOrder(String workOrderId, String operatorId, String operatorName,
                               String operatorPhone, String controllerCode, String robotCode) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) return;

        // Darwin 工单：在状态机执行前先绑定主控和机器人设备
        if (Integer.valueOf(2).equals(order.getSource()) && controllerCode != null) {
            bindDarwinDevices(workOrderId, controllerCode, robotCode);
        }

        workOrderStateMachine.startWorkOrder(workOrderId, operatorId, operatorName, operatorPhone);

        // 重新加载（状态机已更新 status / actualStartTime）
        order = this.getById(workOrderId);
        if (order == null) return;

        WorkOrderDevice master = findMasterDevice(workOrderId);
        if (master != null && master.getDeviceCode() != null) {
            try {
                deviceWebSocketServer.pushStartedWorkOrder(master.getDeviceCode(),
                        objectMapper.writeValueAsString(order));
            } catch (Exception e) {
                log.warn("[startWorkOrder] WebSocket 推送工单失败 workOrderId={}", workOrderId, e);
            }
        }

        // Darwin 工单：向机器人下发开始采集指令
        if (Integer.valueOf(2).equals(order.getSource()) && dataCollectCommandService != null
                && robotCode != null) {
            sendStartCollect(workOrderId, order, robotCode, operatorName);
        }
    }

    @Override
    public void submitWorkOrder(String workOrderId, String operator) {
        workOrderStateMachine.submitWorkOrder(workOrderId, operator);

        WorkOrder order = this.getById(workOrderId);
        if (order != null) {
            // 向机器人下发停止采集指令
            if (dataCollectCommandService != null && Integer.valueOf(2).equals(order.getSource())) {
                sendStopCollect(workOrderId, order);
            }
            // 推送最新工单列表（已提交的工单不在 PENDING/STARTED 中，前端自动感知）
            WorkOrderDevice robotDevice = workOrderDeviceMapper.selectOne(
                    new LambdaQueryWrapper<WorkOrderDevice>()
                            .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                            .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.ROBOT)
                            .last("LIMIT 1"));
            if (robotDevice != null && robotDevice.getDeviceCode() != null) {
                pushDarwinWorkOrdersForDevice(robotDevice.getDeviceCode());
            }
        }
    }

    private void bindDarwinDevices(String workOrderId, String controllerCode, String robotCode) {
        List<WorkOrderDevice> devices = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        IotDevice controller = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, controllerCode));
        if (controller != null) {
            WorkOrderDevice wd = new WorkOrderDevice();
            wd.setWorkOrderId(workOrderId);
            wd.setDeviceCode(controller.getDeviceCode());
            wd.setDeviceId(controller.getId());
            wd.setDeviceName(controller.getDeviceName());
            wd.setDeviceType(DeviceConstant.DeviceType.CONTROLLER);
            wd.setCreateTime(now);
            devices.add(wd);
        } else {
            log.warn("[Darwin] 主控设备不存在，跳过绑定 controllerCode={} workOrderId={}", controllerCode, workOrderId);
        }

        if (robotCode != null) {
            IotDevice robot = iotDeviceMapper.selectOne(
                    new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, robotCode));
            if (robot != null) {
                WorkOrderDevice wd = new WorkOrderDevice();
                wd.setWorkOrderId(workOrderId);
                wd.setDeviceCode(robot.getDeviceCode());
                wd.setDeviceId(robot.getId());
                wd.setDeviceName(robot.getDeviceName());
                wd.setDeviceType(DeviceConstant.DeviceType.ROBOT);
                wd.setCreateTime(now);
                devices.add(wd);
            } else {
                log.warn("[Darwin] 机器人设备不存在，跳过绑定 robotCode={} workOrderId={}", robotCode, workOrderId);
            }
        }

        if (!devices.isEmpty()) {
            bindDevices(workOrderId, devices);
        }
    }

    private void sendStartCollect(String workOrderId, WorkOrder order, String robotCode, String operatorName) {
        try {
            StartCollectCmd.CollectParams params = StartCollectCmd.CollectParams.builder()
                    .primarySceneEn(order.getLevel1SceneNameEn())
                    .secondarySceneEn(order.getLevel2SceneNameEn())
                    .collectionItemNameEn(order.getCollectionItemNameEn())
                    .operatorName(operatorName)
                    .tenantId(order.getTenantId())
                    .build();
            dataCollectCommandService.sendStartCollect(robotCode, workOrderId, params);
            log.info("[Darwin] 开始采集指令已下发 workOrderId={} robotCode={}", workOrderId, robotCode);
        } catch (Exception e) {
            log.warn("[Darwin] 开始采集指令下发失败 workOrderId={} robotCode={}", workOrderId, robotCode, e);
        }
    }

    private void sendStopCollect(String workOrderId, WorkOrder order) {
        WorkOrderDevice robotDevice = workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                        .in(WorkOrderDevice::getDeviceType,
                                List.of(DeviceConstant.DeviceType.ROBOT, "ROBOT"))
                        .last("LIMIT 1"));
        if (robotDevice == null || robotDevice.getDeviceCode() == null) {
            log.warn("[Darwin] 提交工单时未找到机器人设备，跳过停止采集 workOrderId={}", workOrderId);
            return;
        }
        try {
            StopCollectCmd.CollectParams params = StopCollectCmd.CollectParams.builder()
                    .primarySceneEn(order.getLevel1SceneNameEn())
                    .secondarySceneEn(order.getLevel2SceneNameEn())
                    .collectionItemNameEn(order.getCollectionItemNameEn())
                    .operatorName(order.getOperatorName())
                    .tenantId(order.getTenantId())
                    .build();
            dataCollectCommandService.sendStopCollect(robotDevice.getDeviceCode(), workOrderId, params);
            log.info("[Darwin] 停止采集指令已下发 workOrderId={} robotCode={}", workOrderId, robotDevice.getDeviceCode());
        } catch (Exception e) {
            log.warn("[Darwin] 停止采集指令下发失败 workOrderId={}", workOrderId, e);
        }
    }

    @Override
    public void fillTimeoutReason(String workOrderId, String reason, String source) {
        workOrderStateMachine.fillTimeoutReason(workOrderId, reason, source);
    }

    @Override
    public void auditWorkOrder(String workOrderId, String result, String comment, String auditor) {
        workOrderStateMachine.auditWorkOrder(workOrderId, result, comment, auditor);
    }

    @Override
    public void deleteWorkOrder(String workOrderId) {
        workOrderStateMachine.deleteWorkOrder(workOrderId);
    }

    @Override
    public void closeWorkOrder(String workOrderId, String reason, String closer) {
        workOrderStateMachine.closeWorkOrder(workOrderId, reason, closer);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder createWorkOrderWithDevices(WorkOrder order, List<WorkOrderDevice> devices) {
        this.save(order);
        bindDevices(order.getId(), devices);
        return order;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder editWorkOrderWithDevices(WorkOrder updated, List<WorkOrderDevice> devices) {
        WorkOrder existing = this.getById(updated.getId());
        if (existing == null) {
            throw new IllegalArgumentException("工单不存在: " + updated.getId());
        }
        if (!"PENDING".equals(existing.getStatus())) {
            throw new IllegalStateException("仅允许编辑未开始的工单");
        }
        String complianceId = existing.getComplianceId();
        existing.setAgentId(updated.getAgentId());
        existing.setAgentName(updated.getAgentName());
        existing.setTaskName(updated.getTaskName());
        existing.setDepartmentId(updated.getDepartmentId());
        existing.setDepartmentName(updated.getDepartmentName());
        existing.setComplianceId(updated.getComplianceId());
        existing.setCurrency(updated.getCurrency());
        existing.setUnitPrice(updated.getUnitPrice());
        existing.setTotalPrice(updated.getTotalPrice());
        existing.setRemark(updated.getRemark());
        existing.setPlanStartTime(updated.getPlanStartTime());
        existing.setPlanEndTime(updated.getPlanEndTime());
        existing.setUpdateBy(updated.getUpdateBy());
        this.updateById(existing);
        bindDevices(existing.getId(), devices);
        // 根据complianceId判断当前工单合规配置是否有其他工单绑定，若无，则更新工单合规配置的使用状态
        updateComplianceApplyStatus(complianceId, updated.getComplianceId());
        return existing;
    }

    @Override
    public void pushStartedWorkOrders() {
        LocalDateTime now = LocalDateTime.now();
        List<WorkOrder> startedOrders = this.list(new LambdaQueryWrapper<WorkOrder>()
                .eq(WorkOrder::getStatus, "STARTED")
                .eq(WorkOrder::getDelFlag, 0)
                .gt(WorkOrder::getPlanEndTime, now));
        if (startedOrders.isEmpty()) {
            return;
        }
        for (WorkOrder order : startedOrders) {
            WorkOrderDevice master = findMasterDevice(order.getId());
            if (master == null || master.getDeviceCode() == null) {
                log.warn("[pushStartedWorkOrders] 工单无主控设备，跳过: workOrderId={}", order.getId());
                continue;
            }
            try {
                String workOrderJson = objectMapper.writeValueAsString(order);
                deviceWebSocketServer.pushStartedWorkOrder(master.getDeviceCode(), workOrderJson);
            } catch (Exception e) {
                log.warn("[pushStartedWorkOrders] WebSocket 推送失败: workOrderId={}, err={}", order.getId(), e.getMessage());
            }
        }
        log.debug("[pushStartedWorkOrders] 推送进行中工单 {} 条", startedOrders.size());
    }

    @Override
    public IPage<WorkOrderOperationRecordDTO> pageWorkOrderOperationRecords(
            Page<WorkOrder> page,
            String controllerCode) {
        return workOrderMapper.pageWorkOrderOperationRecords(page, controllerCode);
    }

    /**
     * 更新合规配置的应用状态
     * <p>
     * 逻辑：
     * 1. 检查旧的合规配置（complianceId）是否还有其他工单在使用，如果没有则设置为未应用（0）
     * 2. 新的合规配置（newComplianceId）被当前工单使用，设置为已应用（1）
     *
     * @param oldComplianceId 旧的合规配置ID
     * @param newComplianceId 新的合规配置ID
     */
    private void updateComplianceApplyStatus(String oldComplianceId, String newComplianceId) {
        // 1. 检查旧合规配置是否还被其他工单使用
        if (oldComplianceId != null && !oldComplianceId.isEmpty()) {
            long count = this.count(new LambdaQueryWrapper<WorkOrder>()
                    .eq(WorkOrder::getComplianceId, oldComplianceId)
                    .eq(WorkOrder::getDelFlag, 0));

            // 如果没有其他工单使用该合规配置，则更新为未应用状态
            if (count == 0) {
                WorkOrderComplianceConfig oldConfig = complianceConfigMapper.selectById(oldComplianceId);
                if (oldConfig != null && oldConfig.getApplyStatus() != null && oldConfig.getApplyStatus() == 1) {
                    oldConfig.setApplyStatus(0);
                    complianceConfigMapper.updateById(oldConfig);
                    log.info("[updateComplianceApplyStatus] 合规配置 {} 无工单绑定，更新为未应用状态", oldComplianceId);
                }
            }
        }

        // 2. 将新合规配置标记为已应用
        if (newComplianceId != null && !newComplianceId.isEmpty()) {
            WorkOrderComplianceConfig newConfig = complianceConfigMapper.selectById(newComplianceId);
            if (newConfig != null && (newConfig.getApplyStatus() == null || newConfig.getApplyStatus() == 0)) {
                newConfig.setApplyStatus(1);
                complianceConfigMapper.updateById(newConfig);
                log.info("[updateComplianceApplyStatus] 合规配置 {} 被工单绑定，更新为已应用状态", newComplianceId);
            }
        }
    }

    private static final DateTimeFormatter DARWIN_DT_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @Transactional(rollbackFor = Exception.class)
    public WorkOrder upsertWorkOrderFromDarwin(String workOrderId, String tenant,
                                               WorkOrderCreateMsg.WorkOrderItem item,
                                               String traceId, String deviceCode) {
        // 设备不存在时忽略该工单，避免写入无法绑定的孤立工单
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
            // 仅在实际执行了更新（非 STARTED/SUBMITTED/COMPLETED 跳过）时重新绑定设备
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
        // 进行中/已提交/已完成：Darwin 侧修改不予同步
        if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.SUBMITTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.COMPLETED.equals(status)) {
            log.info("[Darwin] 工单状态为 {}，跳过更新 workOrderId={}", status, order.getId());
            return order;
        }
        applyDarwinFields(order, tenant, item);
        // TIMEOUT / CLOSED 状态重置为 PENDING
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

    /**
     * 将机器人设备绑定到工单（ROBOT 类型）。
     * 先删除该工单已有的 ROBOT 绑定再插入，保证幂等；CONTROLLER 绑定不受影响。
     */
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
        // 删除旧的 ROBOT 绑定（CONTROLLER 绑定保留）
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

    /** 将 Darwin 消息字段写入 WorkOrder 对象（新建/更新共用） */
    private void applyDarwinFields(WorkOrder order, String tenant,
                                   WorkOrderCreateMsg.WorkOrderItem item) {
        WorkOrderCreateMsg.CollectionPlan plan = item.getCollectionPlan();
        order.setTaskName(plan != null ? plan.getName() : null);
        order.setPlanStartTime(parseDateTime(plan != null ? plan.getBeginAt() : null));
        order.setPlanEndTime(parseDateTime(plan != null ? plan.getEndAt() : null));
        order.setTaskDesc(formatTaskDesc(item.getCollectionItem()));
        order.setQuotaTotal(parseQuota(item.getQuotaValue()));
        order.setTenantId(tenant);
        order.setAgentId(tenant);
        order.setAgentName(resolveTenantName(tenant));
        order.setDepartmentId(darwinProps.getDefaultDepartmentId());
        order.setDepartmentName(darwinProps.getDefaultDepartmentName());
        WorkOrderCreateMsg.CollectionItem ci = item.getCollectionItem();
        if (ci != null) {
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteWorkOrderFromDarwin(String workOrderId) {
        WorkOrder order = this.getById(workOrderId);
        if (order == null) {
            log.warn("[Darwin] 工单不存在，跳过删除 workOrderId={}", workOrderId);
            return;
        }
        String status = order.getStatus();
        // 进行中/已提交/已完成：Darwin 侧删除不予同步
        if (WorkOrderConstant.ORDER_STATUS.STARTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.SUBMITTED.equals(status)
                || WorkOrderConstant.ORDER_STATUS.COMPLETED.equals(status)) {
            log.info("[Darwin] 工单状态为 {}，跳过删除 workOrderId={}", status, workOrderId);
            return;
        }
        this.removeById(workOrderId);
        log.info("[Darwin] 工单删除完成 workOrderId={}", workOrderId);
    }

    @Override
    public void pushDarwinWorkOrdersForDevice(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) return;

        // 仅对机器人设备推送，非 ROBOT 类型直接跳过
        IotDevice device = iotDeviceMapper.selectOne(
                new LambdaQueryWrapper<IotDevice>().eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null || DeviceConstant.DeviceTypeInteger.ROBOT != device.getDeviceType()) return;

        List<WorkOrderDevice> robotBindings = workOrderDeviceMapper.selectList(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getDeviceCode, deviceCode)
                        .eq(WorkOrderDevice::getDeviceType, DeviceConstant.DeviceType.ROBOT));
        if (robotBindings.isEmpty()) return;

        List<String> orderIds = robotBindings.stream()
                .map(WorkOrderDevice::getWorkOrderId).distinct().toList();

        List<WorkOrder> orders = this.list(new LambdaQueryWrapper<WorkOrder>()
                .in(WorkOrder::getId, orderIds)
                .in(WorkOrder::getStatus, WorkOrderConstant.ORDER_STATUS.PENDING, WorkOrderConstant.ORDER_STATUS.STARTED)
//                .eq(WorkOrder::getSource, 2)
                .eq(WorkOrder::getDelFlag, 0)
                .orderByAsc(WorkOrder::getPlanStartTime));
        if (orders.isEmpty()) return;

        try {
            String listJson = objectMapper.writeValueAsString(orders);
            deviceWebSocketServer.pushDarwinWorkOrderList(deviceCode, listJson);
        } catch (Exception e) {
            log.warn("[Darwin] 推送工单列表失败 deviceCode={}", deviceCode, e);
            return;
        }
        log.info("[Darwin] 已批量推送工单 {} 条至 deviceCode={}", orders.size(), deviceCode);
    }

    /** actions 格式化为 "1.xxx，2.xxx，3.xxx" */
    private static String formatTaskDesc(WorkOrderCreateMsg.CollectionItem item) {
        if (item == null || item.getActions() == null || item.getActions().isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        List<WorkOrderCreateMsg.Action> actions = item.getActions();
        for (int i = 0; i < actions.size(); i++) {
            if (i > 0) sb.append("teleop");
            sb.append(i + 1).append(".").append(actions.get(i).getName());
        }
        return sb.toString();
    }

    private LocalDateTime parseDateTime(String value) {
        if (value == null || value.isBlank()) return null;
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
        if (value == null || value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            log.warn("[Darwin] quotaValue 解析失败 value={}", value);
            return null;
        }
    }
}
