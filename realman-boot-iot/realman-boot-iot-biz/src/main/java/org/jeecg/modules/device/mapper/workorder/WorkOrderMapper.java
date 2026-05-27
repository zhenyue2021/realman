package org.jeecg.modules.device.mapper.workorder;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.dto.WorkOrderOperationRecordDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrder;

import java.util.List;

@Mapper
public interface WorkOrderMapper extends BaseMapper<WorkOrder> {

    /**
     * 按主控设备码分页查询关联工单的操作记录
     *
     * @param page           分页参数（WorkOrder 类型）
     * @param controllerCode 主控设备码
     */
    IPage<WorkOrderOperationRecordDTO> pageWorkOrderOperationRecords(
            Page<WorkOrder> page,
            @Param("controllerCode") String controllerCode);
    /**
     * 按机器人设备代码集合查询关联工单
     * work_order.id = work_order_device.work_order_id and
     * work_order_device.device_type = 1 and work_order_device.device_code in (robotCodes)
     *
     * @param robotCodes 机器人设备码集合
     */
    List<WorkOrder> listWorkOrders(List<String> robotCodes);
}
