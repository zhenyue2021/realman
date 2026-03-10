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
}

