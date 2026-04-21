package org.jeecg.modules.device.service.security.impl;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.service.security.IDeviceSecurityService;
import org.springframework.stereotype.Service;

import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeviceSecurityServiceImpl implements IDeviceSecurityService {

    private final SysAuthFeignClient sysAuthFeignClient;

    @Override
    public void assertAdminOrOps(String username) {
        if ("admin".equalsIgnoreCase(username)) {
            return;
        }

        Set<String> roles = sysAuthFeignClient.getUserRoleSet(username);
        if (roles != null && roles.contains("yunwei")) {
            return;
        }

        throw new RuntimeException("无权限：仅超级管理员/运维人员可访问");
    }
}
