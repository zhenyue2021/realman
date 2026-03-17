package org.jeecg.modules.device.service.workorder;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;

import java.util.List;

public interface IWorkOrderComplianceConfigService extends IService<WorkOrderComplianceConfig> {

    IPage<WorkOrderComplianceConfig> pageConfigs(Page<WorkOrderComplianceConfig> page,
                                                 String agentId,
                                                 String enterpriseId,
                                                 Integer applyStatus);

    WorkOrderComplianceConfig createConfig(WorkOrderComplianceConfig config);

    WorkOrderComplianceConfig updateConfig(String id, WorkOrderComplianceConfig config);

    void deleteConfig(String id);

    List<WorkOrderComplianceConfig> listForExport(String agentId, String enterpriseId, Integer applyStatus);
}

