package org.jeecg.modules.device.api.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderComplianceApiServiceImpl implements WorkOrderComplianceApiService {

    private final IWorkOrderComplianceConfigService configService;

    @Override
    public IPage<WorkOrderComplianceConfig> pageConfigs(Page<WorkOrderComplianceConfig> page,
                                                        WorkOrderComplianceQueryDTO query) {
        return configService.pageConfigs(page, query.getAgentId(), query.getStatus());
    }

    @Override
    public WorkOrderComplianceConfig create(WorkOrderComplianceConfig config, String operator) {
        config.setCreateBy(operator);
        return configService.createConfig(config);
    }

    @Override
    public WorkOrderComplianceConfig update(String id, WorkOrderComplianceConfig config, String operator) {
        config.setUpdateBy(operator);
        return configService.updateConfig(id, config);
    }

    @Override
    public void delete(String id) {
        configService.deleteConfig(id);
    }

    @Override
    public List<WorkOrderComplianceConfig> listForExport(WorkOrderComplianceQueryDTO query) {
        return configService.listForExport(query.getAgentId(), query.getStatus());
    }
}

