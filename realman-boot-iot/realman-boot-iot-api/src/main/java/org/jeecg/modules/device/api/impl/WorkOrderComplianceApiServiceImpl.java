package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigPageVo;
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
    public IPage<WorkOrderComplianceConfigPageVo> pageConfigs(Page<WorkOrderComplianceConfigPageVo> page,
                                                        WorkOrderComplianceQueryDTO query) {
        long current = page != null ? page.getCurrent() : (query.getPageNo() != null ? query.getPageNo() : 1);
        long size = page != null ? page.getSize() : (query.getPageSize() != null ? query.getPageSize() : 20);

        Page<WorkOrderComplianceConfig> pageQuery = new Page<>(current, size);
        IPage<WorkOrderComplianceConfig> pagedConfigs = configService.pageConfigs(
                pageQuery,
                query.getAgentId(),
                query.getEnterpriseId(),
                query.getApplyStatus()
        );

        Page<WorkOrderComplianceConfigPageVo> result = new Page<>(
                pagedConfigs.getCurrent(),
                pagedConfigs.getSize(),
                pagedConfigs.getTotal()
        );
        List<WorkOrderComplianceConfigPageVo> records = pagedConfigs.getRecords() == null ? List.of() :
                pagedConfigs.getRecords().stream().map(cfg -> {
                    WorkOrderComplianceConfigPageVo vo = new WorkOrderComplianceConfigPageVo();
                    BeanUtil.copyProperties(cfg, vo);
                    return vo;
                }).toList();
        result.setRecords(records);
        return result;
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
    public WorkOrderComplianceConfigDetailDTO detail(String id) {
        WorkOrderComplianceConfig cfg = configService.getById(id);
        if (cfg == null) {
            return null;
        }
        WorkOrderComplianceConfigDetailDTO dto = new WorkOrderComplianceConfigDetailDTO();
        BeanUtil.copyProperties(cfg, dto);
        return dto;
    }

    @Override
    public List<WorkOrderComplianceConfig> listForExport(WorkOrderComplianceQueryDTO query) {
        return configService.listForExport(query.getAgentId(), query.getEnterpriseId(), query.getApplyStatus());
    }
}

