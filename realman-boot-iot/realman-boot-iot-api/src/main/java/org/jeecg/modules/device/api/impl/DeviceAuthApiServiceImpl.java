package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.util.ContentDispositionUtil;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.api.DeviceAuthApiService;
import org.jeecg.modules.device.component.DeviceServiceComponent;
import org.jeecg.modules.device.dto.DeviceAuthDTO;
import org.jeecg.modules.device.dto.DeviceAuthDetailDTO;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.SysDepartLiteMapper;
import org.jeecg.modules.device.mapper.SysTenantLiteMapper;
import org.jeecg.modules.device.mapper.SysUserDepartLiteMapper;
import org.jeecg.modules.device.mapper.SysUserTenantLiteMapper;
import org.jeecg.modules.device.service.IIotDeviceAuthService;
import org.jeecg.modules.device.service.security.IDeviceSecurityService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceAuthApiServiceImpl implements DeviceAuthApiService {

    private final IIotDeviceAuthService authService;
    private final SysTenantLiteMapper tenantMapper;
    private final SysDepartLiteMapper departMapper;
    private final SysUserTenantLiteMapper userTenantLiteMapper;
    private final SysUserDepartLiteMapper userDepartLiteMapper;
    private final IotDeviceMapper iotDeviceMapper;
    private final IDeviceSecurityService deviceSecurityService;
    private final DeviceServiceComponent deviceComponent;

    @Override
    public IPage<DeviceAuthDTO> page(HttpServletRequest request, DeviceAuthQueryDTO query) {
        assertAdminOrOps(request);
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        String username = safeUsername(request);
        IPage<IotDeviceAuth> entityPage = authService.queryAuthPage(new Page<>(pageNo, pageSize), query, username, true);

        Page<DeviceAuthDTO> dtoPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        List<DeviceAuthDTO> dtoRecords = entityPage.getRecords() == null ? List.of() :
                entityPage.getRecords().stream().map(this::toDto).toList();
        dtoPage.setRecords(dtoRecords);
        return dtoPage;
    }

    @Override
    public DeviceAuthDTO create(HttpServletRequest request, DeviceAuthDTO dto) {
        assertAdminOrOps(request);
        IotDeviceAuth auth = new IotDeviceAuth();
        BeanUtil.copyProperties(dto, auth);
        validateAuth(auth);
        auth.setCreateBy(safeUsername(request));
        authService.save(auth);
        return toDto(auth);
    }

    @Override
    public DeviceAuthDTO update(HttpServletRequest request, String id, DeviceAuthDTO dto) {
        assertAdminOrOps(request);
        IotDeviceAuth auth = new IotDeviceAuth();
        BeanUtil.copyProperties(dto, auth);
        auth.setId(id);
        validateAuth(auth);
        auth.setUpdateBy(safeUsername(request));
        authService.updateById(auth);
        return toDto(auth);
    }

    @Override
    public void delete(HttpServletRequest request, String id) {
        assertAdminOrOps(request);
        authService.removeById(id);
    }

    @Override
    public DeviceAuthDetailDTO detail(HttpServletRequest request, String id) {
        assertAdminOrOps(request);
        IotDeviceAuth auth = authService.getById(id);
        if (auth == null || (auth.getDelFlag() != null && auth.getDelFlag() == 1)) {
            throw new RuntimeException("授权不存在");
        }
        DeviceAuthDetailDTO dto = new DeviceAuthDetailDTO();
        BeanUtil.copyProperties(auth, dto);
        return dto;
    }

    @Override
    public List<OptionDTO> tenantOptions(HttpServletRequest request) {
        assertAdminOrOps(request);
        return tenantMapper.listAllTenants();
    }

    @Override
    public List<OptionDTO> tenantUserOptions(HttpServletRequest request, Integer tenantId) {
        assertAdminOrOps(request);
        return userTenantLiteMapper.listUsersByTenantId(tenantId);
    }

    @Override
    public List<OptionDTO> enterpriseUserOptions(HttpServletRequest request, String enterpriseId) {
        assertAdminOrOps(request);
        return userDepartLiteMapper.listUsersByDepartId(enterpriseId);
    }

    @Override
    public List<OptionTreeDTO> enterpriseOptionsTree(HttpServletRequest request) {
        assertAdminOrOps(request);
        return deviceComponent.buildEnterpriseTree(departMapper.listEnterpriseTreeRows());
    }

    @Override
    public List<OptionDTO> availableDevices(HttpServletRequest request, Integer deviceType) {
        assertAdminOrOps(request);
        return iotDeviceMapper.listAvailableDevices(deviceType);
    }

    @Override
    public List<OptionDTO> authQueryRobotOptions(HttpServletRequest request) {
        assertAdminOrOps(request);
        return iotDeviceMapper.listDevicesForAuthQuery(1);
    }

    @Override
    public List<OptionDTO> authQueryControllerOptions(HttpServletRequest request) {
        assertAdminOrOps(request);
        return iotDeviceMapper.listDevicesForAuthQuery(2);
    }

    @Override
    public List<OptionDTO> authQueryAuthUsers(HttpServletRequest request) {
        assertAdminOrOps(request);
        return iotDeviceMapper.listAuthUsers();
    }

    @Override
    public ResponseEntity<byte[]> export(HttpServletRequest request, DeviceAuthQueryDTO query) {
        assertAdminOrOps(request);
        String username = safeUsername(request);
        boolean superAdmin = "admin".equalsIgnoreCase(username);
        byte[] bytes = authService.exportAuthList(query, username, superAdmin);
        String filename = "设备授权管理_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDispositionUtil.attachment(filename))
                .body(bytes);
    }

    private DeviceAuthDTO toDto(IotDeviceAuth auth) {
        if (auth == null) return null;
        DeviceAuthDTO dto = new DeviceAuthDTO();
        BeanUtil.copyProperties(auth, dto);
        return dto;
    }

    private String safeUsername(HttpServletRequest request) {
        try {
            return JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
            return null;
        }
    }

    private void validateAuth(IotDeviceAuth auth) {
        if (auth == null) {
            throw new RuntimeException("参数错误");
        }
        boolean tenantBlank = auth.getTenantId() == null || auth.getTenantId().isBlank();
        boolean enterpriseBlank = auth.getEnterpriseId() == null || auth.getEnterpriseId().isBlank();
        if (tenantBlank && enterpriseBlank) {
            throw new RuntimeException("租户ID与企业ID不能同时为空");
        }
        if (auth.getControllerId() == null || auth.getControllerId().isBlank()) {
            throw new RuntimeException("主控设备ID不能为空");
        }
        if (auth.getDeviceId() == null || auth.getDeviceId().isBlank()) {
            throw new RuntimeException("机器人设备ID不能为空");
        }
    }

    private void assertAdminOrOps(HttpServletRequest request) {
        deviceSecurityService.assertAdminOrOps(request == null ? null : safeUsername(request));
    }

}

