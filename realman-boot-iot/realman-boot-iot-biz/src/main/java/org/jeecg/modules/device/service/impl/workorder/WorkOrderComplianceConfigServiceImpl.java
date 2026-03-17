package org.jeecg.modules.device.service.impl.workorder;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.mapper.workorder.WorkOrderComplianceConfigMapper;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkOrderComplianceConfigServiceImpl
        extends ServiceImpl<WorkOrderComplianceConfigMapper, WorkOrderComplianceConfig>
        implements IWorkOrderComplianceConfigService {

    @Override
    public IPage<WorkOrderComplianceConfig> pageConfigs(Page<WorkOrderComplianceConfig> page, String agentId, Integer status) {
        LambdaQueryWrapper<WorkOrderComplianceConfig> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrderComplianceConfig::getAgentId, agentId);
        }
        if (status != null) {
            wrapper.eq(WorkOrderComplianceConfig::getStatus, status);
        }
        wrapper.eq(WorkOrderComplianceConfig::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrderComplianceConfig::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public WorkOrderComplianceConfig createConfig(WorkOrderComplianceConfig config) {
        this.save(config);
        return config;
    }

    @Override
    public WorkOrderComplianceConfig updateConfig(String id, WorkOrderComplianceConfig config) {
        WorkOrderComplianceConfig db = this.getById(id);
        if (db != null && db.getStatus() != null && db.getStatus() == 1) {
            throw new IllegalStateException("已启用的合规配置不允许编辑");
        }
        config.setId(id);
        this.updateById(config);
        return config;
    }

    @Override
    public void deleteConfig(String id) {
        WorkOrderComplianceConfig db = this.getById(id);
        if (db != null && db.getStatus() != null && db.getStatus() == 1) {
            throw new IllegalStateException("已启用的合规配置不允许删除");
        }
        this.removeById(id);
    }

    @Override
    public List<WorkOrderComplianceConfig> listForExport(String agentId, Integer status) {
        LambdaQueryWrapper<WorkOrderComplianceConfig> wrapper = new LambdaQueryWrapper<>();
        if (agentId != null && !agentId.isEmpty()) {
            wrapper.eq(WorkOrderComplianceConfig::getAgentId, agentId);
        }
        if (status != null) {
            wrapper.eq(WorkOrderComplianceConfig::getStatus, status);
        }
        wrapper.eq(WorkOrderComplianceConfig::getDelFlag, 0);
        wrapper.orderByDesc(WorkOrderComplianceConfig::getCreateTime);
        return this.list(wrapper);
    }
}

