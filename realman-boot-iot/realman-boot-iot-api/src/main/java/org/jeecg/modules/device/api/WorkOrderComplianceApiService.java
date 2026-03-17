package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;

import java.util.List;

public interface WorkOrderComplianceApiService {

    IPage<WorkOrderComplianceConfig> pageConfigs(Page<WorkOrderComplianceConfig> page,
                                                 WorkOrderComplianceQueryDTO query);

    WorkOrderComplianceConfig create(WorkOrderComplianceConfig config, String operator);

    WorkOrderComplianceConfig update(String id, WorkOrderComplianceConfig config, String operator);

    void delete(String id);

    List<WorkOrderComplianceConfig> listForExport(WorkOrderComplianceQueryDTO query);
}

