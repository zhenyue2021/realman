package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.RobotDeviceApiService;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.DeviceConfigViewDTO;
import org.jeecg.modules.device.dto.DeviceOperationLogViewDTO;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceStatusViewDTO;
import org.jeecg.modules.device.dto.RobotDevicePageItemDTO;
import org.jeecg.modules.device.entity.*;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.jeecg.modules.device.vo.RobotDeviceDetailVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RobotDeviceApiServiceImpl implements RobotDeviceApiService {

    private static final int DEVICE_TYPE_ROBOT = 1;

    private final IIotDeviceService deviceService;

    @Override
    public IPage<RobotDevicePageItemDTO> pageRobots(Page<?> page, DeviceRequestDTO requestDTO) {
        long current = page != null ? page.getCurrent() : (requestDTO != null && requestDTO.getPageNo() != null ? requestDTO.getPageNo() : 1);
        long size = page != null ? page.getSize() : (requestDTO != null && requestDTO.getPageSize() != null ? requestDTO.getPageSize() : 10);

        DeviceRequestDTO dto = requestDTO == null ? new DeviceRequestDTO() : requestDTO;
        dto.setDeviceType(DEVICE_TYPE_ROBOT);
        IPage<IotDevice> devicePage = deviceService.queryDevicePage(new Page<>((int) current, (int) size), dto);
        List<IotDevice> devices = devicePage.getRecords() == null ? List.of() : devicePage.getRecords();
        if (devices.isEmpty()) {
            Page<RobotDevicePageItemDTO> out = new Page<>(devicePage.getCurrent(), devicePage.getSize(), devicePage.getTotal());
            out.setRecords(List.of());
            return out;
        }
        List<String> deviceIds = devices.stream()
                .map(IotDevice::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        List<RobotDevicePageItemDTO> records = devices.stream().map(this::toPageItem).toList();
        //  授权生效/失效时间（按当前租户）
        Map<String, IotDeviceAuth> authByControllerId = deviceService.loadTenantAuth(deviceIds, dto.getCurrentTenantId(), DeviceConstant.DeviceType.ROBOT);

        Page<RobotDevicePageItemDTO> out = new Page<>(devicePage.getCurrent(), devicePage.getSize(), devicePage.getTotal());
        out.setRecords(records);
        return out;
    }


    @Override
    public RobotDevicePageItemDTO getRobotDeviceView(String deviceId) {
        IotDevice d = deviceService.getById(deviceId);
        if (d == null) {
            throw new RuntimeException("设备不存在: " + deviceId);
        }
        if (!Objects.equals(d.getDeviceType(), DEVICE_TYPE_ROBOT)) {
            throw new RuntimeException("设备类型不匹配：该ID不是机器人设备");
        }
        return toPageItem(d);
    }

    @Override
    public RobotDeviceDetailVO getRobotDeviceDetailAgg(String deviceId) {
        DeviceDetailVO vo = deviceService.getDeviceDetail(deviceId);
        if (vo != null && vo.getDevice() != null && !Objects.equals(vo.getDevice().getDeviceType(), DEVICE_TYPE_ROBOT)) {
            throw new RuntimeException("设备类型不匹配：该ID不是机器人设备");
        }
        RobotDeviceDetailVO out = new RobotDeviceDetailVO();
        if (vo.getDevice() != null) {
            out.setDevice(toPageItem(vo.getDevice()));
        }
        out.setOnline(vo.getOnline());
        out.setLastHeartbeatTime(vo.getLastHeartbeatTime());
        out.setRealtimeStatus(vo.getRealtimeStatus());
        out.setDeviceConfigs(mapConfigs(vo.getDeviceConfigs()));
        out.setLatestStatus(mapStatus(vo.getLatestStatus()));
        out.setRecentLogs(mapLogs(vo.getRecentLogs()));
        return out;
    }

    @Override
    public RobotDevicePageItemDTO toPageItem(IotDevice device) {
        if (device == null) {
            return null;
        }
        RobotDevicePageItemDTO item = new RobotDevicePageItemDTO();
        BeanUtil.copyProperties(device, item);
        item.setRunningStatus(device.getStatus());
        return item;
    }

    private List<DeviceConfigViewDTO> mapConfigs(List<IotDeviceConfig> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(c -> {
            DeviceConfigViewDTO dto = new DeviceConfigViewDTO();
            BeanUtil.copyProperties(c, dto);
            return dto;
        }).toList();
    }

    private DeviceStatusViewDTO mapStatus(IotDeviceStatus s) {
        if (s == null) {
            return null;
        }
        DeviceStatusViewDTO dto = new DeviceStatusViewDTO();
        BeanUtil.copyProperties(s, dto);
        return dto;
    }

    private List<DeviceOperationLogViewDTO> mapLogs(List<IotDeviceOperationLog> list) {
        if (list == null || list.isEmpty()) {
            return List.of();
        }
        return list.stream().map(l -> {
            DeviceOperationLogViewDTO dto = new DeviceOperationLogViewDTO();
            BeanUtil.copyProperties(l, dto);
            return dto;
        }).toList();
    }
}
