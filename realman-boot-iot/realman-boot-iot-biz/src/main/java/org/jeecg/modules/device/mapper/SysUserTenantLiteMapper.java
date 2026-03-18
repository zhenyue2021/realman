package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.dto.OptionDTO;

import java.util.List;

@Mapper
public interface SysUserTenantLiteMapper {

    /**
     * 查询指定租户下用户列表（仅正常状态）。
     * 返回用户 id + username（如需 realname 可调整为 su.realname）。
     */
    @Select("""
            SELECT su.id       AS id,
                   su.username AS name
            FROM sys_user_tenant sut
            JOIN sys_user su ON su.id = sut.user_id
            WHERE sut.tenant_id = #{tenantId}
              AND sut.status = '1'
              AND (su.del_flag IS NULL OR su.del_flag = 0)
              AND (su.status IS NULL OR su.status = 1)
            ORDER BY su.username ASC
            """)
    List<OptionDTO> listUsersByTenantId(@Param("tenantId") Integer tenantId);
}

