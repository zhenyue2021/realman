package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigPageVo;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

public interface WorkOrderComplianceApiService {

    IPage<WorkOrderComplianceConfigPageVo> pageConfigs(Page<WorkOrderComplianceConfigPageVo> page,
                                                       WorkOrderComplianceQueryDTO query);

    WorkOrderComplianceConfig create(WorkOrderComplianceCreateDTO config, String operator);

    WorkOrderComplianceConfig update(String id, WorkOrderComplianceCreateDTO config, String operator);

    void delete(String id);

    WorkOrderComplianceConfigDetailDTO detail(String id);

    List<WorkOrderComplianceConfig> listForExport(WorkOrderComplianceQueryDTO query);

    List<OptionDTO> tenantOptions(HttpServletRequest request);

    List<OptionTreeDTO> enterpriseOptionsTree(HttpServletRequest request);
}

