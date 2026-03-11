package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;

public interface IIotDeviceAuthService extends IService<IotDeviceAuth> {

    IPage<IotDeviceAuth> queryAuthPage(Page<IotDeviceAuth> page,
                                       DeviceAuthQueryDTO query,
                                       String currentUsername,
                                       boolean superAdmin);

    /**
     * 导出授权列表为 Excel（条件与分页查询一致，最多 10000 条）
     */
    byte[] exportAuthList(DeviceAuthQueryDTO query, String currentUsername, boolean superAdmin);
}

