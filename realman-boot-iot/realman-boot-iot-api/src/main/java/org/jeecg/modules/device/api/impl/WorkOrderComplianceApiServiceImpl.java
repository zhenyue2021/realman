package org.jeecg.modules.device.api.impl;

import cn.hutool.core.bean.BeanUtil;
import com.alibaba.fastjson.JSONObject;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.system.vo.DictModel;
import org.jeecg.common.util.TokenUtils;
import org.jeecg.modules.device.api.WorkOrderComplianceApiService;
import org.jeecg.modules.device.component.DeviceServiceComponent;
import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigDetailDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceConfigPageVo;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceCreateDTO;
import org.jeecg.modules.device.dto.workorder.WorkOrderComplianceQueryDTO;
import org.jeecg.modules.device.entity.workorder.WorkOrderComplianceConfig;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.util.RequestUtil;
import org.jeecg.modules.device.service.workorder.IWorkOrderComplianceConfigService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkOrderComplianceApiServiceImpl implements WorkOrderComplianceApiService {

    private final IWorkOrderComplianceConfigService configService;
    private final DeviceServiceComponent deviceComponent;
    private final SysAuthFeignClient sysAuthFeignClient;

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
    public WorkOrderComplianceConfig create(WorkOrderComplianceCreateDTO config, String operator) {
        WorkOrderComplianceConfig complianceConfig = new WorkOrderComplianceConfig();
        BeanUtil.copyProperties(config, complianceConfig);
        complianceConfig.setCreateBy(operator);
        complianceConfig.setCreateTime(LocalDateTime.now());
        return configService.createConfig(complianceConfig);
    }

    @Override
    public WorkOrderComplianceConfig update(String id, WorkOrderComplianceCreateDTO configDto, String operator) {
        WorkOrderComplianceConfig config = new WorkOrderComplianceConfig();
        BeanUtil.copyProperties(configDto, config);
        config.setUpdateBy(operator);
        config.setUpdateTime(LocalDateTime.now());
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

    @Override
    public List<OptionTreeDTO> enterpriseOptionsTree(HttpServletRequest request) {
        String username = RequestUtil.safeUsername(request);
        if (StrUtil.isBlank(username)) {
            return List.of();
        }
        List<String> anchorIds = sysAuthFeignClient.listValidEnterpriseIdsByUsername(username);
        if (anchorIds == null || anchorIds.isEmpty()) {
            return List.of();
        }
        List<EnterpriseNodeRowDTO> rows = toEnterpriseRows(sysAuthFeignClient.listEnterpriseTreeRows());
        return deviceComponent.buildEnterpriseTreeForAnchors(rows, new HashSet<>(anchorIds));
    }

    @Override
    public List<OptionDTO> tenantOptions(HttpServletRequest request) {
        String tenantId = TokenUtils.getTenantIdByRequest(request);
        if (StrUtil.isBlank(tenantId)) {
            log.debug("租户下拉缺少请求租户标识（租户ID / X-Tenant-Id）");
            return List.of();
        }
        List<DictModel> all = sysAuthFeignClient.listActiveTenants();
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        return all.stream()
                .filter(m -> m != null && tenantId.equals(m.getValue()))
                .map(m -> new OptionDTO(m.getValue(), m.getText()))
                .collect(Collectors.toList());
    }

    // ── 转换工具 ─────────────────────────────────────────────────────────

    /** JSONObject{id,parentId,name,orgCategory} → EnterpriseNodeRowDTO */
    private static List<EnterpriseNodeRowDTO> toEnterpriseRows(List<JSONObject> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().map(j -> {
            EnterpriseNodeRowDTO r = new EnterpriseNodeRowDTO();
            r.setId(j.getString("id"));
            r.setParentId(j.getString("parentId"));
            r.setName(j.getString("name"));
            r.setOrgCategory(j.getString("orgCategory"));
            return r;
        }).collect(Collectors.toList());
    }
}
