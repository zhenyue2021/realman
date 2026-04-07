package org.jeecg.modules.device.service.impl.master;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.WorkOrderDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.workorder.WorkOrder;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.entity.workorder.WorkOrderDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.workorder.WorkOrderDeviceMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;
import org.springframework.stereotype.Service;

/**
 * 主控登录工单解析：通过工单查找关联机器人、组装工单 DTO（含合规性配置）。
 */
@Service
@RequiredArgsConstructor
public class MasterWorkOrderResolveService {

    private final WorkOrderDeviceMapper workOrderDeviceMapper;
    private final IotDeviceMapper deviceMapper;
    private final IWorkOrderComplianceConfigService workOrderConfigService;

    /**
     * 从工单设备绑定记录中查找关联的机器人实体，未绑定机器人时返回 null。
     */
    public IotDevice resolveRobotByWorkOrder(String workOrderId) {
        WorkOrderDevice bind = workOrderDeviceMapper.selectOne(
                new LambdaQueryWrapper<WorkOrderDevice>()
                        .eq(WorkOrderDevice::getWorkOrderId, workOrderId)
                        .eq(WorkOrderDevice::getDeviceType, "1")
                        .last("LIMIT 1")
        );
        if (bind == null) {
            return null;
        }
        // 优先使用实际设备 ID（作业中可能已换绑设备）
        String deviceId = StrUtil.isNotBlank(bind.getActualDeviceId()) ? bind.getActualDeviceId() : bind.getDeviceId();
        if (deviceId != null) {
            return deviceMapper.selectById(deviceId);
        }
        return null;
    }

    /**
     * 组装工单 DTO，附带合规性配置详情。
     */
    public WorkOrderDTO buildWorkOrderDto(WorkOrder order) {
        WorkOrderComplianceConfig complianceConfig = workOrderConfigService.getById(order.getComplianceId());
        WorkOrderDTO dto = new WorkOrderDTO();
        BeanUtil.copyProperties(order, dto);
        dto.setWorkOrderComplianceConfig(complianceConfig);
        return dto;
    }
}
