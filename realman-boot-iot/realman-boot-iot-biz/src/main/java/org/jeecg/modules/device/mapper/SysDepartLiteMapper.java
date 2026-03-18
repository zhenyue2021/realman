package org.jeecg.modules.device.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionDTO;

import java.util.List;

@Mapper
public interface SysDepartLiteMapper {



    /**
     * 企业树：查询公司/子公司并返回 parentId 用于组装树
     */
    @Select("""
            SELECT id          AS id,
                   parent_id   AS parentId,
                   depart_name AS name,
                   org_category AS orgCategory
            FROM sys_depart
            WHERE (del_flag IS NULL OR del_flag = '0')
              AND (status IS NULL OR status = '1')
              AND org_category IN ('1','4')
            ORDER BY depart_order ASC, create_time DESC
            """)
    List<EnterpriseNodeRowDTO> listEnterpriseTreeRows();
}

