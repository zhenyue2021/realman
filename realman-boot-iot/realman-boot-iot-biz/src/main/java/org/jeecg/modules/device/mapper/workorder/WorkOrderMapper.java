package org.jeecg.modules.device.mapper.workorder;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.jeecg.modules.device.entity.workorder.WorkOrder;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {
}

