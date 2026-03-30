package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.MasterDeviceApiService;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.MasterDevicePageItemDTO;
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.entity.IotMasterLoginLog;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.mapper.IotMasterLoginLogMapper;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.service.workorder.IWorkOrderService;
import org.jeecg.modules.device.vo.WorkOrderOperationRecordVO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class MasterDeviceApiServiceImpl implements MasterDeviceApiService {

    private static final int DEVICE_TYPE_CONTROLLER = 2;

    private final IIotDeviceService deviceService;
    private final IotMasterLoginLogMapper loginLogMapper;
    private final IotDeviceAuthMapper deviceAuthMapper;
    private final IWorkOrderService workOrderService;

    private final RestTemplate restTemplate = defaultRestTemplate();

    @Override
    public IPage<MasterDevicePageItemDTO> pageControllers(Page<?> page, DeviceRequestDTO requestDTO) {
        long current = page != null ? page.getCurrent() : (requestDTO.getPageNo() != null ? requestDTO.getPageNo() : 1);
        long size = page != null ? page.getSize() : (requestDTO.getPageSize() != null ? requestDTO.getPageSize() : 10);

        // 1) 先走原有设备分页（已包含数据权限逻辑）
        DeviceRequestDTO dto = requestDTO == null ? new DeviceRequestDTO() : requestDTO;
        dto.setDeviceType(DEVICE_TYPE_CONTROLLER);
        IPage<IotDevice> devicePage = deviceService.queryDevicePage(new Page<>((int) current, (int) size), dto);
        List<IotDevice> devices = devicePage.getRecords() == null ? List.of() : devicePage.getRecords();
        if (devices.isEmpty()) {
            Page<MasterDevicePageItemDTO> out = new Page<>(devicePage.getCurrent(), devicePage.getSize(), devicePage.getTotal());
            out.setRecords(List.of());
            return out;
        }

        List<String> controllerIds = devices.stream()
                .map(IotDevice::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        // 2) 最近登录操作员（每个 controller_id 取 login_time 最大的一条）
        Map<String, IotMasterLoginLog> lastLoginByControllerId = loadLastLogin(controllerIds);

        // 3) 授权生效/失效时间（按当前租户）
        Map<String, IotDeviceAuth> authByControllerId = deviceService.loadTenantAuth(controllerIds, dto.getCurrentTenantId(), DeviceConstant.DeviceType.CONTROLLER);

        // 4) 经纬度反解地址（尽力而为）
        Map<String, String> addressCache = new HashMap<>();

        List<MasterDevicePageItemDTO> records = devices.stream().map(d -> {
            MasterDevicePageItemDTO item = new MasterDevicePageItemDTO();
            BeanUtil.copyProperties(d, item);
            item.setRunningStatus(d.getStatus());

            IotMasterLoginLog last = lastLoginByControllerId.get(d.getId());
            if (last != null) {
                item.setLastLoginOperatorId(last.getOperatorId());
                item.setLastLoginOperatorName(last.getOperatorName());
                item.setLastLoginTime(last.getLoginTime());
            }

            IotDeviceAuth auth = authByControllerId.get(d.getId());
            if (auth != null) {
                item.setAuthEffectiveTime(auth.getEffectiveTime());
                item.setAuthExpireTime(auth.getExpireTime());
            }

//            String addr = reverseGeocodeCached(d.getLatitude(), d.getLongitude(), addressCache);
//            item.setAddress(addr);
            return item;
        }).toList();

        Page<MasterDevicePageItemDTO> out = new Page<>(devicePage.getCurrent(), devicePage.getSize(), devicePage.getTotal());
        out.setRecords(records);
        return out;
    }

    @Override
    public IPage<WorkOrderOperationRecordVO> pageWorkOrderOperationRecords(
            Page<?> page,
            String controllerCode) {
        Page<WorkOrder> workOrderPage = new Page<>(page.getCurrent(), page.getSize());
        IPage<WorkOrderOperationRecordDTO> dtoPage =
                workOrderService.pageWorkOrderOperationRecords(workOrderPage, controllerCode);

        // DTO → VO 转换，保留分页元数据
        List<WorkOrderOperationRecordVO> voList = dtoPage.getRecords().stream()
                .map(dto -> {
                    WorkOrderOperationRecordVO vo = new WorkOrderOperationRecordVO();
                    vo.setWorkOrderId(dto.getWorkOrderId());
                    vo.setControllerCode(dto.getControllerCode());
                    vo.setRobotDeviceCode(dto.getRobotDeviceCode());
                    vo.setOperatorStartTime(dto.getActualStartTime());
                    vo.setOperatorEndTime(dto.getSubmitTime());
                    vo.setDurationSeconds(dto.getDurationSeconds());
                    return vo;
                }).toList();

        Page<WorkOrderOperationRecordVO> voPage =
                new Page<>(dtoPage.getCurrent(), dtoPage.getSize(), dtoPage.getTotal());
        voPage.setRecords(voList);
        return voPage;
    }



    private Map<String, IotMasterLoginLog> loadLastLogin(List<String> controllerIds) {
        if (controllerIds == null || controllerIds.isEmpty()) {
            return Map.of();
        }
        List<IotMasterLoginLog> logs = loginLogMapper.selectList(new LambdaQueryWrapper<IotMasterLoginLog>()
                .in(IotMasterLoginLog::getControllerId, controllerIds)
                .orderByDesc(IotMasterLoginLog::getLoginTime));
        if (logs == null || logs.isEmpty()) {
            return Map.of();
        }
        Map<String, IotMasterLoginLog> map = new HashMap<>();
        for (IotMasterLoginLog l : logs) {
            if (l.getControllerId() == null) continue;
            map.putIfAbsent(l.getControllerId(), l);
        }
        return map;
    }


    private String reverseGeocodeCached(BigDecimal latitude, BigDecimal longitude, Map<String, String> cache) {
        if (latitude == null || longitude == null) {
            return null;
        }
        String key = latitude.toPlainString() + "," + longitude.toPlainString();
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String addr = reverseGeocode(latitude.toPlainString(), longitude.toPlainString());
        cache.put(key, addr);
        return addr;
    }

    /**
     * 使用 Nominatim 反向地理编码（无需 key）。失败返回 null。
     */
    private String reverseGeocode(String lat, String lon) {
        try {
            String url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=" + lat + "&lon=" + lon;
            @SuppressWarnings("unchecked")
            Map<String, Object> resp = restTemplate.getForObject(url, Map.class);
            if (resp == null) return null;
            Object displayName = resp.get("display_name");
            return displayName != null ? String.valueOf(displayName) : null;
        } catch (RestClientException e) {
            return null;
        }
    }

    private static RestTemplate defaultRestTemplate() {
        RestTemplate rt = new RestTemplate();
        rt.getInterceptors().add((request, body, execution) -> {
            HttpHeaders headers = request.getHeaders();
            headers.setAccept(List.of(MediaType.APPLICATION_JSON));
            // Nominatim 要求带 User-Agent
            headers.set(HttpHeaders.USER_AGENT, "realman-boot-iot/1.0");
            return execution.execute(request, body);
        });
        return rt;
    }
}

