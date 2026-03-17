package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.MasterDevicePageItemDTO;

public interface MasterDeviceApiService {

    IPage<MasterDevicePageItemDTO> pageControllers(Page<?> page, DeviceRequestDTO requestDTO);
}

