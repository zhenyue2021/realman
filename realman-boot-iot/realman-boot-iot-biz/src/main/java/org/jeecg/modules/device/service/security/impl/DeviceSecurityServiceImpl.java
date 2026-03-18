package org.jeecg.modules.device.service.security.impl;

import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.mapper.SysUserRoleLiteMapper;
import org.jeecg.modules.device.service.security.IDeviceSecurityService;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class DeviceSecurityServiceImpl implements IDeviceSecurityService {

    private final SysUserRoleLiteMapper userRoleLiteMapper;

    @Override
    public void assertAdminOrOps(String username) {
        if (username != null && "admin".equalsIgnoreCase(username)) {
            return;
        }

        Set<String> roles = loadRoleCodes(username);
        if (roles.contains("yunwei")) {
            return;
        }

        throw new RuntimeException("无权限：仅超级管理员/运维人员可访问");
    }

    private Set<String> loadRoleCodes(String username) {
        if (username == null || username.isBlank()) {
            return Set.of();
        }
        List<String> list = userRoleLiteMapper.listRoleCodesByUsername(username);
        if (list == null || list.isEmpty()) {
            return Set.of();
        }
        Set<String> out = new HashSet<>();
        for (String s : list) {
            if (s == null) continue;
            String v = s.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }
}

