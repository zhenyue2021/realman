package org.jeecg.modules.system.service.security.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.system.mapper.SysUserRoleMapper;
import org.jeecg.modules.system.service.security.ISystemSecurityService;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 系统模块权限能力（biz 层）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemSecurityServiceImpl implements ISystemSecurityService {

    private final SysUserRoleMapper userRoleMapper;


    private static final Set<String> OPS_ROLES = Set.of("yunwei", "admin");

    @Override
    public boolean assertAdminOrOps(String username) {
        if ("admin".equalsIgnoreCase(username)) {
            return true;
        }

        List<String> roles = userRoleMapper.getRoleByUserName(username);
        if (roles == null) {
            return false;
        }

        return roles.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(OPS_ROLES::contains);
    }
}

