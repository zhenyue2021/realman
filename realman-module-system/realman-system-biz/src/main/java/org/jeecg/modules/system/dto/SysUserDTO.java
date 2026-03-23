package org.jeecg.modules.system.dto;

import lombok.Data;

@Data
public class SysUserDTO {
    /**
     * 用户名
     */
    private String realname;
    /**
     * 部门id
     */
    private String departId;
    /**
     * 租户id
     */
    private String tenantId;
    /**
     * 角色id
     */
    private String selectedroles;
    /**
     * 是否是管理员
     */
    private boolean isAdmin;

}
