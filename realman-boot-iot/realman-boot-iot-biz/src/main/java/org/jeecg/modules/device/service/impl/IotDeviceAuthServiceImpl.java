package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.IotDeviceAuthMapper;
import org.jeecg.modules.device.service.IIotDeviceAuthService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class IotDeviceAuthServiceImpl
        extends ServiceImpl<IotDeviceAuthMapper, IotDeviceAuth>
        implements IIotDeviceAuthService {

    @Override
    public IPage<IotDeviceAuth> queryAuthPage(Page<IotDeviceAuth> page,
                                              DeviceAuthQueryDTO query,
                                              String currentUsername,
                                              boolean superAdmin) {
        LambdaQueryWrapper<IotDeviceAuth> wrapper = new LambdaQueryWrapper<>();

        if (query != null) {
            wrapper.eq(query.getTenantId() != null && !query.getTenantId().isEmpty(),
                    IotDeviceAuth::getTenantId, query.getTenantId());
            wrapper.eq(query.getEnterpriseId() != null && !query.getEnterpriseId().isEmpty(),
                    IotDeviceAuth::getEnterpriseId, query.getEnterpriseId());
            wrapper.eq(query.getControllerId() != null && !query.getControllerId().isEmpty(),
                    IotDeviceAuth::getControllerId, query.getControllerId());
            wrapper.eq(query.getControllerCode() != null && !query.getControllerCode().isEmpty(),
                    IotDeviceAuth::getControllerCode, query.getControllerCode());
            wrapper.eq(query.getDeviceId() != null && !query.getDeviceId().isEmpty(),
                    IotDeviceAuth::getDeviceId, query.getDeviceId());
            wrapper.eq(query.getDeviceCode() != null && !query.getDeviceCode().isEmpty(),
                    IotDeviceAuth::getDeviceCode, query.getDeviceCode());
            wrapper.eq(query.getStatus() != null,
                    IotDeviceAuth::getStatus, query.getStatus());
            if (query.getStartEffectiveTime() != null) {
                wrapper.ge(IotDeviceAuth::getEffectiveTime, query.getStartEffectiveTime());
            }
            if (query.getEndEffectiveTime() != null) {
                wrapper.le(IotDeviceAuth::getExpireTime, query.getEndEffectiveTime());
            }
        }

        wrapper.orderByDesc(IotDeviceAuth::getCreateTime);
        return this.page(page, wrapper);
    }

    @Override
    public byte[] exportAuthList(DeviceAuthQueryDTO query, String currentUsername, boolean superAdmin) {
        int max = 10000;
        IPage<IotDeviceAuth> page = queryAuthPage(new Page<>(1, max), query, currentUsername, superAdmin);
        try {
            return org.jeecg.modules.device.util.DeviceExcelExportUtil.exportAuthList(page.getRecords());
        } catch (Exception e) {
            throw new RuntimeException("导出授权Excel失败", e);
        }
    }
}

