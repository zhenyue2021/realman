package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;

@Service
@RequiredArgsConstructor
public class WorkOrderComplianceConfigServiceImpl
        extends ServiceImpl<WorkOrderComplianceConfigMapper, WorkOrderComplianceConfig>
        implements IWorkOrderComplianceConfigService {
}

