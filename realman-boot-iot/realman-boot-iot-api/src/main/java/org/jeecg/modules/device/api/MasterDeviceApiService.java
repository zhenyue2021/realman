package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.MasterDevicePageItemDTO;
import org.jeecg.modules.device.vo.WorkOrderOperationRecordVO;

public interface MasterDeviceApiService {

    IPage<MasterDevicePageItemDTO> pageControllers(Page<?> page, DeviceRequestDTO requestDTO);

    /**
     * 按主控设备码分页查询关联工单操作记录
     *
     * @param page           分页参数
     * @param controllerCode 主控设备码（必填）
     */
    IPage<WorkOrderOperationRecordVO> pageWorkOrderOperationRecords(
            Page<?> page,
            String controllerCode);
}

