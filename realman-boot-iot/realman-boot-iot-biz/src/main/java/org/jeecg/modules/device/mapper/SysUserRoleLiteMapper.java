package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface SysUserRoleLiteMapper {

    @Select("""
            SELECT r.role_code
            FROM sys_user u
            JOIN sys_user_role ur ON ur.user_id = u.id
            JOIN sys_role r ON r.id = ur.role_id
            WHERE u.username = #{username}
              AND (u.del_flag IS NULL OR u.del_flag = 0)
            """)
    List<String> listRoleCodesByUsername(@Param("username") String username);
}

