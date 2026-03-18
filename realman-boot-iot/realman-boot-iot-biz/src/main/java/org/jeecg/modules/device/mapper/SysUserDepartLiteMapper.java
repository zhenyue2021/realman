package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.dto.OptionDTO;

import java.util.List;

@Mapper
public interface SysUserDepartLiteMapper {

    /**
     * 查询指定企业/部门下用户列表。
     * 返回用户 id + realname。
     */
    @Select("""
            SELECT su.id       AS id,
                   su.realname AS name
            FROM sys_user_depart sud
            JOIN sys_user su ON su.id = sud.user_id
            WHERE sud.dep_id = #{departId}
              AND (su.del_flag IS NULL OR su.del_flag = 0)
              AND (su.status IS NULL OR su.status = 1)
            ORDER BY su.create_time ASC
            """)
    List<OptionDTO> listUsersByDepartId(@Param("departId") String departId);
}

