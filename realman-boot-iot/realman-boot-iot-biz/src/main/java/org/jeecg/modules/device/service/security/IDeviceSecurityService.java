package org.jeecg.modules.device.service.security;

/**
 * IoT 模块权限能力（biz 层）
 */
public interface IDeviceSecurityService {

    /**
     * 仅允许超级管理员/运维访问，否则抛异常
     */
    void assertAdminOrOps(String username);
}

