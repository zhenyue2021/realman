package org.jeecg.modules.deviceinfo.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootBizTipException;
import org.jeecg.modules.deviceinfo.contract.dto.BindingUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceBatchQueryRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceHeartbeatSnapshotRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceListQuery;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOccupancyEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceOnlineEventRequest;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceRegisterWriteRequest;
import org.jeecg.modules.deviceinfo.contract.dto.FirmwareVersionUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.LifecycleUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.dto.PageResult;
import org.jeecg.modules.deviceinfo.contract.dto.TestFlagUpdateRequest;
import org.jeecg.modules.deviceinfo.contract.enums.DeviceType;
import org.jeecg.modules.deviceinfo.contract.enums.LifecycleStage;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyDetail;
import org.jeecg.modules.deviceinfo.contract.enums.OccupancyState;
import org.jeecg.modules.deviceinfo.contract.enums.OnlineStatus;
import org.jeecg.modules.deviceinfo.entity.DeviceInfo;
import org.jeecg.modules.deviceinfo.mapper.DeviceInfoMapper;
import org.jeecg.modules.deviceinfo.service.IDeviceInfoService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 设备信息基础服务（SSOT）业务实现。
 *
 * <p>写路径遵循设备基座详细设计 2.3 的字段子集划分原则：本服务不做审计留痕
 * （那是设备管理业务平台的职责），也不做权限校验（只做租户过滤），只负责
 * 把各写入方（设备管理业务平台/设备通信中台/OTA 平台）各自负责的字段子集
 * 落到 {@code device_info} 单表。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceInfoServiceImpl extends ServiceImpl<DeviceInfoMapper, DeviceInfo> implements IDeviceInfoService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_BATCH_QUERY_LIMIT = 500;
    private static final int MAX_BATCH_QUERY_LIMIT = 5000;

    @Override
    public DeviceInfoDTO getDevice(String deviceId) {
        DeviceInfo entity = getById(deviceId);
        if (entity == null) {
            throw new JeecgBootBizTipException("设备不存在：" + deviceId);
        }
        return toDTO(entity);
    }

    @Override
    public DeviceInfoDTO getDeviceByCode(String deviceCode) {
        DeviceInfo entity = getOne(new LambdaQueryWrapper<DeviceInfo>()
                .eq(DeviceInfo::getDeviceCode, deviceCode)
                .last("LIMIT 1"));
        if (entity == null) {
            throw new JeecgBootBizTipException("设备不存在：" + deviceCode);
        }
        return toDTO(entity);
    }

    @Override
    public List<DeviceInfoDTO> batchQuery(DeviceBatchQueryRequest request) {
        LambdaQueryWrapper<DeviceInfo> wrapper = new LambdaQueryWrapper<>();
        if (!CollectionUtils.isEmpty(request.getDeviceIds())) {
            wrapper.in(DeviceInfo::getDeviceId, request.getDeviceIds());
        }
        if (!CollectionUtils.isEmpty(request.getDeviceCodes())) {
            wrapper.in(DeviceInfo::getDeviceCode, request.getDeviceCodes());
        }
        if (StringUtils.hasText(request.getTenantId())) {
            wrapper.eq(DeviceInfo::getTenantId, request.getTenantId());
        }
        if (request.getDeviceType() != null) {
            wrapper.eq(DeviceInfo::getDeviceType, request.getDeviceType().name());
        }
        if (StringUtils.hasText(request.getDeviceModel())) {
            wrapper.eq(DeviceInfo::getDeviceModel, request.getDeviceModel());
        }
        if (Boolean.TRUE.equals(request.getOnlyOnline())) {
            wrapper.eq(DeviceInfo::getOnlineStatus, OnlineStatus.ONLINE.name());
        }
        // 默认 500 条（能力清单/设备基座详细设计 2.3），调用方可通过 limit 显式提高，
        // 但受 MAX_BATCH_QUERY_LIMIT 硬上限保护，避免误传超大值拖垮 SSOT 查询。
        // 此前曾固定 LIMIT 500 不接受调用方覆盖，导致 OTA max_batch_devices（默认 1000）
        // 配置的批量任务在设备数超过 500 时被静默截断而非按预期报 ERR_BATCH_DEVICE_LIMIT_EXCEEDED。
        int effectiveLimit = request.getLimit() != null && request.getLimit() > 0
                ? Math.min(request.getLimit(), MAX_BATCH_QUERY_LIMIT)
                : DEFAULT_BATCH_QUERY_LIMIT;
        wrapper.last("LIMIT " + effectiveLimit);
        return list(wrapper).stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Override
    public PageResult<DeviceInfoDTO> list(DeviceListQuery query) {
        LambdaQueryWrapper<DeviceInfo> wrapper = new LambdaQueryWrapper<>();
        if (StringUtils.hasText(query.getTenantId())) {
            wrapper.eq(DeviceInfo::getTenantId, query.getTenantId());
        }
        if (query.getDeviceType() != null) {
            wrapper.eq(DeviceInfo::getDeviceType, query.getDeviceType().name());
        }
        if (StringUtils.hasText(query.getDeviceModel())) {
            wrapper.eq(DeviceInfo::getDeviceModel, query.getDeviceModel());
        }
        if (query.getOnlineStatus() != null) {
            wrapper.eq(DeviceInfo::getOnlineStatus, query.getOnlineStatus().name());
        }
        if (StringUtils.hasText(query.getOccupancyState())) {
            wrapper.eq(DeviceInfo::getOccupancyState, query.getOccupancyState());
        }
        if (query.getTestDevice() != null) {
            wrapper.eq(DeviceInfo::getTestDevice, query.getTestDevice());
        }
        int pageNo = query.getPageNo() == null || query.getPageNo() < 1 ? 1 : query.getPageNo();
        int pageSize = query.getPageSize() == null || query.getPageSize() < 1 ? 20 : query.getPageSize();
        IPage<DeviceInfo> page = page(new Page<>(pageNo, pageSize), wrapper);
        List<DeviceInfoDTO> records = page.getRecords().stream().map(this::toDTO).collect(Collectors.toList());
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void register(DeviceRegisterWriteRequest request) {
        DeviceInfo existing = getById(request.getDeviceId());
        if (existing != null) {
            log.warn("[device-info] register 幂等：deviceId={} 已存在，忽略重复写入", request.getDeviceId());
            return;
        }
        DeviceInfo entity = new DeviceInfo();
        entity.setDeviceId(request.getDeviceId());
        entity.setDeviceCode(request.getDeviceCode());
        entity.setDeviceType(request.getDeviceType().name());
        entity.setTenantId(request.getTenantId());
        entity.setDeviceModel(request.getDeviceModel());
        entity.setDeviceName(request.getDeviceName());
        entity.setMacAddress(request.getMacAddress());
        entity.setOnlineStatus(OnlineStatus.UNACTIVATED.name());
        entity.setOccupancyState(OccupancyState.OFFLINE.name());
        entity.setLifecycleStage(LifecycleStage.ACTIVATED.name());
        entity.setTestDevice(false);
        save(entity);
    }

    @Override
    public void reportOnlineEvent(DeviceOnlineEventRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(request.getDeviceId());
        update.setOnlineStatus(request.getEventType().name());
        if (request.getEventType() == OnlineStatus.ONLINE) {
            update.setLastOnlineAt(request.getOccurredAt());
        } else if (request.getEventType() == OnlineStatus.OFFLINE) {
            update.setLastOfflineAt(request.getOccurredAt());
            update.setOfflineReason(request.getOfflineReason());
            // 离线时联动四态为 OFFLINE，见设备基座详细设计 2.1
            update.setOccupancyState(OccupancyState.OFFLINE.name());
        }
        updateByIdRequired(update, request.getDeviceId());
    }

    @Override
    public void reportOccupancyEvent(DeviceOccupancyEventRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(request.getDeviceId());
        update.setOccupancyState(request.getOccupancyState().name());
        update.setOccupancyDetail(request.getOccupancyDetail() != null ? request.getOccupancyDetail().name() : null);
        updateByIdRequired(update, request.getDeviceId());
    }

    @Override
    public void reportHeartbeatSnapshot(DeviceHeartbeatSnapshotRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(request.getDeviceId());
        update.setIpAddress(request.getIpAddress());
        update.setLastHeartbeatAt(request.getHeartbeatAt() != null ? request.getHeartbeatAt() : LocalDateTime.now());
        update.setResourceSnapshot(writeJson(request.getResourceSnapshot()));
        updateByIdRequired(update, request.getDeviceId());
    }

    @Override
    public void updateFirmwareVersion(String deviceId, FirmwareVersionUpdateRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(deviceId);
        update.setFirmwareVersion(request.getFirmwareVersion());
        update.setFirmwareComponents(writeJson(request.getFirmwareComponents()));
        updateByIdRequired(update, deviceId);
    }

    @Override
    public void updateTestFlag(String deviceId, TestFlagUpdateRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(deviceId);
        update.setTestDevice(request.getTestDevice());
        updateByIdRequired(update, deviceId);
    }

    @Override
    public void updateBinding(String deviceId, BindingUpdateRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(deviceId);
        update.setBoundDeviceIds(writeJson(request.getBoundDeviceIds()));
        updateByIdRequired(update, deviceId);
    }

    @Override
    public void updateLifecycle(String deviceId, LifecycleUpdateRequest request) {
        DeviceInfo update = new DeviceInfo();
        update.setDeviceId(deviceId);
        update.setLifecycleStage(request.getLifecycleStage().name());
        updateByIdRequired(update, deviceId);
    }

    private void updateByIdRequired(DeviceInfo update, String deviceId) {
        boolean ok = updateById(update);
        if (!ok) {
            throw new JeecgBootBizTipException("设备不存在，无法更新：" + deviceId);
        }
    }

    private DeviceInfoDTO toDTO(DeviceInfo entity) {
        DeviceInfoDTO dto = new DeviceInfoDTO();
        dto.setDeviceId(entity.getDeviceId());
        dto.setTenantId(entity.getTenantId());
        dto.setDeviceCode(entity.getDeviceCode());
        dto.setDeviceType(parseEnum(DeviceType.class, entity.getDeviceType()));
        dto.setDeviceModel(entity.getDeviceModel());
        dto.setDeviceName(entity.getDeviceName());
        dto.setMacAddress(entity.getMacAddress());
        dto.setIpAddress(entity.getIpAddress());
        dto.setFirmwareVersion(entity.getFirmwareVersion());
        dto.setFirmwareComponents(readJsonMap(entity.getFirmwareComponents()));
        dto.setOnlineStatus(parseEnum(OnlineStatus.class, entity.getOnlineStatus()));
        dto.setOccupancyState(parseEnum(OccupancyState.class, entity.getOccupancyState()));
        dto.setOccupancyDetail(parseEnum(OccupancyDetail.class, entity.getOccupancyDetail()));
        dto.setLifecycleStage(parseEnum(LifecycleStage.class, entity.getLifecycleStage()));
        dto.setTestDevice(entity.getTestDevice());
        dto.setLocation(readJsonObjectMap(entity.getLocation()));
        dto.setResourceSnapshot(readJsonObjectMap(entity.getResourceSnapshot()));
        dto.setLastHeartbeatAt(entity.getLastHeartbeatAt());
        dto.setLastOnlineAt(entity.getLastOnlineAt());
        dto.setLastOfflineAt(entity.getLastOfflineAt());
        dto.setOfflineReason(entity.getOfflineReason());
        dto.setBoundDeviceIds(readJsonList(entity.getBoundDeviceIds()));
        dto.setComponentSnMap(readJsonMap(entity.getComponentSnMap()));
        dto.setDataVersion(entity.getDataVersion());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Enum.valueOf(type, value);
    }

    private static String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception e) {
            throw new JeecgBootBizTipException("JSON 序列化失败：" + e.getMessage());
        }
    }

    private static Map<String, String> readJsonMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (Exception e) {
            log.warn("[device-info] JSON 解析失败，忽略该字段：{}", e.getMessage());
            return null;
        }
    }

    private static Map<String, Object> readJsonObjectMap(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("[device-info] JSON 解析失败，忽略该字段：{}", e.getMessage());
            return null;
        }
    }

    private static List<String> readJsonList(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return JSON.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("[device-info] JSON 解析失败，忽略该字段：{}", e.getMessage());
            return null;
        }
    }
}
