package org.jeecg.modules.system.service.security;

/**
 * 系统模块权限能力（biz 层）
 */
public interface ISystemSecurityService {

    /**
     * 仅允许超级管理员/运维访问，否则抛异常
     *
     * @param username 登录用户名
     */
    boolean assertAdminOrOps(String username);
}

