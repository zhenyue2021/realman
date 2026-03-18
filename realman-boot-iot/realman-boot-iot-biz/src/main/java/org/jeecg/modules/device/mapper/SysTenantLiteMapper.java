package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.dto.OptionDTO;

import java.util.List;

@Mapper
public interface SysTenantLiteMapper {

    @Select("""
            SELECT CAST(id AS CHAR) AS id,
                   name           AS name
            FROM sys_tenant
            WHERE (status IS NULL OR status = 1)
            ORDER BY id ASC
            """)
    List<OptionDTO> listAllTenants();
}

