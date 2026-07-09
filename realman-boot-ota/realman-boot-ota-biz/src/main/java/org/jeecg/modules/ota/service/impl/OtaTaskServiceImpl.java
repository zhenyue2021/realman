package org.jeecg.modules.ota.service.impl;

import cn.hutool.core.util.IdUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceBatchQueryRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.ota.contract.enums.OtaTaskState;
import org.jeecg.modules.ota.entity.OtaFirmware;
import org.jeecg.modules.ota.entity.OtaTask;
import org.jeecg.modules.ota.entity.OtaTaskDevice;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.mapper.OtaTaskMapper;
import org.jeecg.modules.ota.service.IOtaDownlinkService;
import org.jeecg.modules.ota.service.IOtaFirmwareService;
import org.jeecg.modules.ota.service.IOtaPrecheckService;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.jeecg.modules.ota.service.IOtaTaskService;
import org.jeecg.modules.ota.service.OtaAuditService;
import org.jeecg.modules.ota.service.OtaTaskStateMachineService;
import org.jeecg.modules.ota.vo.BatchSummary;
import org.jeecg.modules.ota.vo.TaskCreateRequest;
import org.jeecg.modules.ota.vo.TaskDTO;
import org.jeecg.modules.ota.vo.TaskDeviceDTO;
import org.jeecg.modules.ota.vo.TaskListQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.jeecg.modules.ota.config.OtaSystemSettingDefaults.*;

/**
 * 升级任务管理实现，对齐 OTA 平台详细设计五章/八章（PRD 4.4、9.5）。
 *
 * <p>已知简化（本轮范围内的务实取舍，均已注释标注）：
 * <ul>
 *   <li>下行通知调用失败时不做后台自动重试扫描，子任务停留在 STARTING，需人工重试；</li>
 *   <li>{@code by_tenant_model} 的"运维人员只能为本租户发起"校验只在携带
 *       {@code X-Operator-Tenant-Id}（超管标记）时放行跨租户，非超管调用时信任
 *       网关/会话已经把 {@code tenant_id} 限定在操作者所属范围——与设备管理业务平台
 *       既有的跨租户校验范围保持一致，不重新实现一套 RBAC；</li>
 *   <li>EXECUTING 阶段取消的 {@code symlink_switched} 上报与 cancel_ack_timeout
 *       兜底逻辑在上行事件消费层处理（见 IUplinkConsumerService），本类的
 *       {@link #cancel} 只负责发起取消请求与处理"尚未下发"的直接取消。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OtaTaskServiceImpl implements IOtaTaskService {

    private static final String ERR_INVALID_STATE = "ERR_INVALID_STATE";
    private static final String ERR_NOT_CANCELABLE = "ERR_NOT_CANCELABLE";
    private static final String ERR_ROLLBACK_IN_PROGRESS = "ERR_ROLLBACK_IN_PROGRESS";
    private static final String ERR_HIGH_RISK_RESTRICTED = "ERR_HIGH_RISK_RESTRICTED";
    private static final String ERR_BATCH_DEVICE_LIMIT_EXCEEDED = "ERR_BATCH_DEVICE_LIMIT_EXCEEDED";

    private final OtaTaskMapper taskMapper;
    private final OtaTaskDeviceMapper taskDeviceMapper;
    private final IOtaFirmwareService firmwareService;
    private final IOtaPrecheckService precheckService;
    private final IOtaSystemSettingService systemSettingService;
    private final OtaTaskStateMachineService stateMachineService;
    private final IOtaDownlinkService downlinkService;
    private final OtaAuditService auditService;
    private final DeviceInfoFeignClient deviceInfoFeignClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO create(TaskCreateRequest request, String operator, String operatorTenantId) {
        OtaFirmware firmware = firmwareService.getRequired(request.getPackageId());
        if (!firmware.getDeviceType().equalsIgnoreCase(request.getDeviceType())) {
            throw new JeecgBootBizTipException("固件包设备类型与请求不一致");
        }

        boolean highRisk = "high_risk".equalsIgnoreCase(firmware.getRiskLevel());
        if (highRisk && !"BY_SN".equalsIgnoreCase(request.getUpgradeMode())) {
            throw new JeecgBootBizTipException(ERR_HIGH_RISK_RESTRICTED + ": high_risk 固件包只允许 by_sn 方式下发");
        }

        List<DeviceInfoDTO> targets = resolveTargetDevices(request);
        if (targets.isEmpty()) {
            throw new JeecgBootBizTipException("没有找到符合条件的目标设备");
        }
        if (!"BY_SN".equalsIgnoreCase(request.getUpgradeMode())) {
            long maxBatchDevices = systemSettingService.getLong(MAX_BATCH_DEVICES);
            if (targets.size() > maxBatchDevices) {
                throw new JeecgBootBizTipException(ERR_BATCH_DEVICE_LIMIT_EXCEEDED + ": 目标设备数（" + targets.size()
                        + "台）超过批量上限（" + maxBatchDevices + "台），请缩小范围或分批创建任务");
            }
        }
        if (highRisk) {
            DeviceInfoDTO device = targets.get(0);
            if (!Boolean.TRUE.equals(device.getTestDevice())) {
                throw new JeecgBootBizTipException(ERR_HIGH_RISK_RESTRICTED + ": high_risk 固件包只能下发给已标记测试设备");
            }
        }

        OtaTask task = buildTask(request, firmware, operator);
        taskMapper.insert(task);

        boolean singleDevice = "BY_SN".equalsIgnoreCase(request.getUpgradeMode());
        List<OtaTaskDevice> created = new ArrayList<>();
        for (DeviceInfoDTO device : targets) {
            OtaTaskDevice subTask = newSubTask(task.getTaskId(), device);
            boolean offline = precheckService.isOffline(device);
            if (offline) {
                subTask.setState(OtaTaskState.PENDING_ONLINE.name());
                taskDeviceMapper.insert(subTask);
                created.add(subTask);
                continue;
            }
            try {
                precheckService.checkDeviceState(device);
                precheckService.checkResources(device);
                precheckService.checkDiskSpaceForFirmware(device, firmware.getFileSizeMb());
                precheckService.checkVersionCompatibility(device, firmware);
                precheckService.checkSignatureNotRevoked(firmware);
            } catch (JeecgBootBizTipException e) {
                if (singleDevice) {
                    throw e;
                }
                log.info("[ota] 批量任务创建时排除不满足前置条件的设备 deviceCode={}: {}", device.getDeviceCode(), e.getMessage());
                continue;
            }
            subTask.setState(OtaTaskState.PENDING.name());
            taskDeviceMapper.insert(subTask);
            created.add(subTask);
        }
        if (created.isEmpty()) {
            throw new JeecgBootBizTipException("全部目标设备均未通过前置校验，任务未创建");
        }

        for (OtaTaskDevice subTask : created) {
            if (OtaTaskState.PENDING.name().equals(subTask.getState())) {
                stateMachineService.dispatchDevice(subTask, firmware);
            }
        }
        stateMachineService.recomputeBatchStatus(task.getTaskId());

        auditService.write("TASK_CREATE", operator, operatorTenantId, request.getTenantId(), "normal",
                task.getTaskId(), firmware.getPackageId(), null,
                Map.of("upgradeMode", request.getUpgradeMode(), "targetCount", created.size()));
        return detail(task.getTaskId());
    }

    @Override
    public PageResult<TaskDTO> list(TaskListQuery query) {
        Page<OtaTask> page = new Page<>(query.getPageNo(), query.getPageSize());
        Page<OtaTask> pageResult = taskMapper.selectPage(page, Wrappers.<OtaTask>lambdaQuery()
                .eq(StringUtils.hasText(query.getDeviceType()), OtaTask::getDeviceType, query.getDeviceType())
                .eq(StringUtils.hasText(query.getStatus()), OtaTask::getStatus, query.getStatus())
                .eq(StringUtils.hasText(query.getTenantId()), OtaTask::getTenantId, query.getTenantId())
                .ge(query.getCreatedAtFrom() != null, OtaTask::getCreatedAt, query.getCreatedAtFrom())
                .le(query.getCreatedAtTo() != null, OtaTask::getCreatedAt, query.getCreatedAtTo())
                .orderByDesc(OtaTask::getCreatedAt));
        List<TaskDTO> records = pageResult.getRecords().stream()
                .map(t -> toDTO(t, false)).collect(Collectors.toList());
        return new PageResult<>(records, pageResult.getTotal(), query.getPageNo(), query.getPageSize());
    }

    @Override
    public TaskDTO detail(String taskId) {
        return toDTO(getRequiredTask(taskId), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO retry(String taskId, String operator) {
        OtaTask task = getRequiredTask(taskId);
        OtaTaskDevice subTask = getSingleSubTask(taskId);
        assertState(subTask, OtaTaskState.FAILED, OtaTaskState.ROLLBACK_FAILED);
        OtaFirmware firmware = firmwareService.getRequired(task.getPackageId());

        subTask.setState(OtaTaskState.PENDING.name());
        subTask.setUpgradeErrorCode(null);
        subTask.setUpgradeErrorMsg(null);
        subTask.setRetryCount(subTask.getRetryCount() == null ? 1 : subTask.getRetryCount() + 1);
        taskDeviceMapper.updateById(subTask);

        stateMachineService.dispatchDevice(subTask, firmware);
        stateMachineService.recomputeBatchStatus(taskId);
        auditService.write("TASK_RETRY", operator, "normal", Map.of("taskId", taskId));
        return detail(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO cancel(String taskId, String operator) {
        OtaTask task = getRequiredTask(taskId);
        OtaTaskDevice subTask = getSingleSubTask(taskId);
        OtaTaskState state = OtaTaskState.valueOf(subTask.getState());

        if (state == OtaTaskState.EXECUTING) {
            OtaFirmware firmware = firmwareService.getRequired(task.getPackageId());
            if (!Boolean.TRUE.equals(firmware.getCancelableInExecuting())) {
                throw new JeecgBootBizTipException(ERR_NOT_CANCELABLE + ": 当前阶段无法中止，请等待 EXECUTING 完成");
            }
            // EXECUTING 阶段的实际终态（CANCELLED / ROLLING_BACK）取决于设备端上报的
            // symlink_switched 字段，由上行事件消费层结合 cancel_ack_timeout 兜底规则判定，
            // 本方法只发起取消下行请求并记录 cancelRequestedAt，不在此处直接落终态。
            subTask.setCancelRequestedAt(LocalDateTime.now());
            taskDeviceMapper.updateById(subTask);
            downlinkService.notifyCancel(subTask);
            log.info("[ota] 已发起 EXECUTING 阶段取消请求，等待设备端 symlink_switched 上报 taskId={}", taskId);
            return detail(taskId);
        }

        List<OtaTaskState> cancellablePreExecuting = List.of(OtaTaskState.PENDING, OtaTaskState.PENDING_ONLINE,
                OtaTaskState.STARTING, OtaTaskState.DOWNLOADING, OtaTaskState.CHECKING, OtaTaskState.EXTRACTING);
        if (!cancellablePreExecuting.contains(state)) {
            throw new JeecgBootBizTipException(ERR_INVALID_STATE + ": 当前状态 " + state + " 不允许取消");
        }
        subTask.setState(OtaTaskState.CANCELLED.name());
        subTask.setStateChangedAt(LocalDateTime.now());
        taskDeviceMapper.updateById(subTask);
        stateMachineService.recomputeBatchStatus(taskId);
        auditService.write("TASK_CANCEL", operator, "normal", Map.of("taskId", taskId));
        return detail(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO rollback(String taskId, String operator) {
        OtaTask task = getRequiredTask(taskId);
        OtaTaskDevice subTask = getSingleSubTask(taskId);
        OtaTaskState state = OtaTaskState.valueOf(subTask.getState());
        if (state == OtaTaskState.ROLLING_BACK) {
            throw new JeecgBootBizTipException(ERR_ROLLBACK_IN_PROGRESS + ": 自动回滚正在进行中，请等待结果");
        }
        assertState(subTask, OtaTaskState.FAILED, OtaTaskState.ROLLBACK_FAILED);

        OtaFirmware firmware = firmwareService.getRequired(task.getPackageId());
        subTask.setState(OtaTaskState.ROLLING_BACK.name());
        subTask.setStateChangedAt(LocalDateTime.now());
        taskDeviceMapper.updateById(subTask);
        downlinkService.notifyRollback(subTask, firmware);
        auditService.write("TASK_ROLLBACK", operator, "normal", Map.of("taskId", taskId));
        return detail(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO retryFailed(String taskId, String operator) {
        OtaTask task = getRequiredTask(taskId);
        if (!List.of("PARTIAL_COMPLETED", "FAILED", "PAUSED").contains(task.getStatus())) {
            throw new JeecgBootBizTipException(ERR_INVALID_STATE + ": 批量任务当前状态 " + task.getStatus() + " 不支持失败设备重试");
        }
        OtaFirmware firmware = firmwareService.getRequired(task.getPackageId());
        List<OtaTaskDevice> failedDevices = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getTaskId, taskId)
                .in(OtaTaskDevice::getState, OtaTaskState.FAILED.name(), OtaTaskState.ROLLBACK_FAILED.name()));
        for (OtaTaskDevice subTask : failedDevices) {
            subTask.setState(OtaTaskState.PENDING.name());
            subTask.setUpgradeErrorCode(null);
            subTask.setUpgradeErrorMsg(null);
            subTask.setRetryCount(subTask.getRetryCount() == null ? 1 : subTask.getRetryCount() + 1);
            taskDeviceMapper.updateById(subTask);
            stateMachineService.dispatchDevice(subTask, firmware);
        }
        stateMachineService.recomputeBatchStatus(taskId);
        auditService.write("TASK_RETRY_FAILED", operator, "normal", Map.of("taskId", taskId, "retriedCount", failedDevices.size()));
        return detail(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO resume(String taskId, Map<String, Object> resumeBody, String operator) {
        OtaTask task = getRequiredTask(taskId);
        if (!"PAUSED".equals(task.getStatus())) {
            throw new JeecgBootBizTipException(ERR_INVALID_STATE + ": 只有 PAUSED 状态的批量任务才能 resume");
        }
        Map<String, Object> body = resumeBody == null ? Map.of() : resumeBody;
        if (body.containsKey("failThreshold")) {
            Object value = body.get("failThreshold");
            task.setActiveFailThresholdSnapshot(value == null ? task.getFailThreshold() : ((Number) value).intValue());
        }
        // 字段缺失（body 中不含 failThreshold key）时快照冻结，保持上次值，不做任何修改
        if (body.get("onThresholdExceeded") instanceof String onThresholdExceeded) {
            task.setOnThresholdExceeded(onThresholdExceeded);
        }
        task.setStatus("IN_PROGRESS");
        task.setResumeCount((task.getResumeCount() == null ? 0 : task.getResumeCount()) + 1);
        taskMapper.updateById(task);

        OtaFirmware firmware = firmwareService.getRequired(task.getPackageId());
        List<OtaTaskDevice> pendingDevices = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getTaskId, taskId)
                .eq(OtaTaskDevice::getState, OtaTaskState.PENDING.name()));
        for (OtaTaskDevice subTask : pendingDevices) {
            stateMachineService.dispatchDevice(subTask, firmware);
        }
        stateMachineService.recomputeBatchStatus(taskId);
        auditService.write("TASK_RESUME", operator, "normal",
                Map.of("taskId", taskId, "activeFailThresholdSnapshot",
                        task.getActiveFailThresholdSnapshot() == null ? "" : task.getActiveFailThresholdSnapshot()));
        return detail(taskId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TaskDTO abort(String taskId, String operator) {
        OtaTask task = getRequiredTask(taskId);
        if (!"PAUSED".equals(task.getStatus())) {
            throw new JeecgBootBizTipException(ERR_INVALID_STATE + ": 只有 PAUSED 状态的批量任务才能 abort");
        }
        List<OtaTaskDevice> notDispatched = taskDeviceMapper.selectList(Wrappers.<OtaTaskDevice>lambdaQuery()
                .eq(OtaTaskDevice::getTaskId, taskId)
                .in(OtaTaskDevice::getState, OtaTaskState.PENDING.name(), OtaTaskState.PENDING_ONLINE.name()));
        for (OtaTaskDevice subTask : notDispatched) {
            subTask.setState(OtaTaskState.CANCELLED.name());
            subTask.setStateChangedAt(LocalDateTime.now());
            taskDeviceMapper.updateById(subTask);
        }
        task.setStatus("IN_PROGRESS");
        task.setStopAllTriggered(true);
        taskMapper.updateById(task);
        stateMachineService.recomputeBatchStatus(taskId);

        auditService.write("TASK_ABORT", operator, "normal", Map.of("taskId", taskId, "cancelledCount", notDispatched.size()));
        return detail(taskId);
    }

    // ------------------------------------------------------------------

    private List<DeviceInfoDTO> resolveTargetDevices(TaskCreateRequest request) {
        String mode = request.getUpgradeMode().toUpperCase();
        DeviceType deviceType = DeviceType.valueOf(request.getDeviceType().toUpperCase());
        if ("BY_SN".equals(mode)) {
            if (!StringUtils.hasText(request.getSn())) {
                throw new JeecgBootBizTipException("upgradeMode=BY_SN 时 sn 必填");
            }
            Result<DeviceInfoDTO> result = deviceInfoFeignClient.getDeviceByCode(request.getSn());
            if (result == null || !result.isSuccess() || result.getResult() == null) {
                throw new JeecgBootBizTipException("设备不存在：" + request.getSn());
            }
            DeviceInfoDTO device = result.getResult();
            if (device.getDeviceType() != deviceType) {
                throw new JeecgBootBizTipException("设备类型与请求不一致：" + request.getSn());
            }
            return List.of(device);
        }

        DeviceBatchQueryRequest query = new DeviceBatchQueryRequest();
        query.setDeviceType(deviceType);
        query.setOnlyOnline(true);
        if ("BY_MODEL".equals(mode)) {
            if (!StringUtils.hasText(request.getModel())) {
                throw new JeecgBootBizTipException("upgradeMode=BY_MODEL 时 model 必填");
            }
            query.setDeviceModel(request.getModel());
        } else if ("BY_TENANT_MODEL".equals(mode)) {
            if (!StringUtils.hasText(request.getModel()) || !StringUtils.hasText(request.getTenantId())) {
                throw new JeecgBootBizTipException("upgradeMode=BY_TENANT_MODEL 时 model 与 tenantId 均必填");
            }
            query.setDeviceModel(request.getModel());
            query.setTenantId(request.getTenantId());
        } else if (!"ALL".equals(mode)) {
            throw new JeecgBootBizTipException("非法 upgradeMode：" + request.getUpgradeMode());
        }
        // 已知限制：SSOT batchQuery 内部硬编码 LIMIT 500（见设备基座详细设计 2.3），
        // 早于 max_batch_devices（默认 1000）配置项存在；目标设备超过 500 台的批量任务
        // 在当前实现下只会取到前 500 台，不会真正触发 ERR_BATCH_DEVICE_LIMIT_EXCEEDED。
        // 需要提高上限时应先调整 SSOT 侧的查询实现（分页遍历），不在本轮 OTA 范围内。
        Result<List<DeviceInfoDTO>> result = deviceInfoFeignClient.batchQuery(query);
        return result != null && result.isSuccess() && result.getResult() != null ? result.getResult() : List.of();
    }

    private OtaTask buildTask(TaskCreateRequest request, OtaFirmware firmware, String operator) {
        OtaTask task = new OtaTask();
        task.setTaskId(IdUtil.fastSimpleUUID());
        task.setDeviceType(request.getDeviceType());
        task.setPackageId(firmware.getPackageId());
        task.setUpgradeMode(request.getUpgradeMode().toUpperCase());
        task.setTargetSelector(writeSelectorJson(request));
        task.setTenantId(request.getTenantId());
        task.setBandwidthLimitMbps(request.getBandwidthLimitMbps());
        task.setFailThresholdType(StringUtils.hasText(request.getFailThresholdType())
                ? request.getFailThresholdType() : systemSettingService.getString(DEFAULT_FAIL_THRESHOLD_TYPE));
        task.setFailThreshold(request.getFailThreshold() != null
                ? request.getFailThreshold() : systemSettingService.getInt(DEFAULT_FAIL_THRESHOLD));
        task.setOnThresholdExceeded(StringUtils.hasText(request.getOnThresholdExceeded())
                ? request.getOnThresholdExceeded() : systemSettingService.getString(DEFAULT_ON_THRESHOLD_EXCEEDED));
        task.setActiveFailThresholdSnapshot(task.getFailThreshold());
        task.setStatus("IN_PROGRESS");
        task.setStopAllTriggered(false);
        task.setResumeCount(0);
        task.setCreatedBy(operator);
        return task;
    }

    private String writeSelectorJson(TaskCreateRequest request) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "sn", request.getSn() == null ? "" : request.getSn(),
                    "model", request.getModel() == null ? "" : request.getModel(),
                    "tenantId", request.getTenantId() == null ? "" : request.getTenantId()));
        } catch (Exception e) {
            return "{}";
        }
    }

    private OtaTaskDevice newSubTask(String taskId, DeviceInfoDTO device) {
        OtaTaskDevice subTask = new OtaTaskDevice();
        subTask.setId(IdUtil.fastSimpleUUID());
        subTask.setTaskId(taskId);
        subTask.setDeviceId(device.getDeviceId());
        subTask.setDeviceCode(device.getDeviceCode());
        subTask.setProgressPct(0);
        subTask.setRetryCount(0);
        subTask.setStateChangedAt(LocalDateTime.now());
        return subTask;
    }

    private OtaTaskDevice getSingleSubTask(String taskId) {
        List<OtaTaskDevice> subTasks = taskDeviceMapper.selectList(
                Wrappers.<OtaTaskDevice>lambdaQuery().eq(OtaTaskDevice::getTaskId, taskId));
        if (subTasks.size() != 1) {
            throw new JeecgBootBizTipException("该操作仅适用于单设备任务，批量任务请使用对应的批量接口（retry-failed/abort）");
        }
        return subTasks.get(0);
    }

    private void assertState(OtaTaskDevice subTask, OtaTaskState... allowed) {
        OtaTaskState current = OtaTaskState.valueOf(subTask.getState());
        for (OtaTaskState state : allowed) {
            if (state == current) {
                return;
            }
        }
        throw new JeecgBootBizTipException(ERR_INVALID_STATE + ": 当前状态 " + current + " 不允许此操作");
    }

    private OtaTask getRequiredTask(String taskId) {
        OtaTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new JeecgBootBizTipException("任务不存在：" + taskId);
        }
        return task;
    }

    private TaskDTO toDTO(OtaTask task, boolean includeSubTasks) {
        TaskDTO dto = new TaskDTO();
        dto.setTaskId(task.getTaskId());
        dto.setDeviceType(task.getDeviceType());
        dto.setPackageId(task.getPackageId());
        dto.setUpgradeMode(task.getUpgradeMode());
        dto.setStatus(task.getStatus());
        dto.setActiveFailThresholdSnapshot(task.getActiveFailThresholdSnapshot());
        dto.setCreatedBy(task.getCreatedBy());
        dto.setCreatedAt(task.getCreatedAt());
        dto.setUpdatedAt(task.getUpdatedAt());

        List<OtaTaskDevice> subTasks = taskDeviceMapper.selectList(
                Wrappers.<OtaTaskDevice>lambdaQuery().eq(OtaTaskDevice::getTaskId, task.getTaskId()));
        dto.setBatchSummary(buildBatchSummary(task, subTasks));
        if (includeSubTasks) {
            dto.setSubTasks(subTasks.stream().map(this::toDeviceDTO).collect(Collectors.toList()));
        }
        return dto;
    }

    private BatchSummary buildBatchSummary(OtaTask task, List<OtaTaskDevice> subTasks) {
        BatchSummary summary = new BatchSummary();
        long total = subTasks.size();
        long completed = subTasks.stream().filter(d -> OtaTaskState.COMPLETED.name().equals(d.getState())).count();
        long cancelled = subTasks.stream().filter(d -> OtaTaskState.CANCELLED.name().equals(d.getState())).count();
        long failed = subTasks.stream().filter(d -> OtaTaskState.FAILED.name().equals(d.getState())
                || OtaTaskState.ROLLBACK_FAILED.name().equals(d.getState())).count();
        long inProgress = total - completed - cancelled - failed;

        summary.setTotal(total);
        summary.setCompleted(completed);
        summary.setFailed(failed);
        summary.setCancelled(cancelled);
        summary.setInProgress(inProgress);
        boolean aborted = Boolean.TRUE.equals(task.getStopAllTriggered()) || cancelled > 0;
        long denominator = aborted ? total - cancelled : total;
        summary.setCompletionRateBasis(aborted ? "executed" : "total");
        summary.setCompletionRatePct(denominator == 0 ? 0 : (int) Math.ceil(completed * 100.0 / denominator));
        summary.setStopAllTriggered(Boolean.TRUE.equals(task.getStopAllTriggered()));
        summary.setThresholdTriggeredAt(task.getThresholdTriggeredAt());
        summary.setOnThresholdExceeded(task.getOnThresholdExceeded());
        summary.setPausedAt(task.getPausedAt());
        summary.setResumeCount(task.getResumeCount() == null ? 0 : task.getResumeCount());
        return summary;
    }

    private TaskDeviceDTO toDeviceDTO(OtaTaskDevice subTask) {
        TaskDeviceDTO dto = new TaskDeviceDTO();
        dto.setDeviceId(subTask.getDeviceId());
        dto.setDeviceCode(subTask.getDeviceCode());
        dto.setState(subTask.getState());
        dto.setProgressPct(subTask.getProgressPct());
        dto.setSubStage(subTask.getSubStage());
        dto.setSigVerifyResult(subTask.getSigVerifyResult());
        dto.setUpgradeErrorCode(subTask.getUpgradeErrorCode());
        dto.setUpgradeErrorMsg(subTask.getUpgradeErrorMsg());
        dto.setRollbackReason(subTask.getRollbackReason());
        dto.setReportedAt(subTask.getReportedAt());
        return dto;
    }
}
