package org.jeecg.modules.device.service;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotDeviceOperationLog;
import java.time.LocalDateTime;

public interface IDeviceOperationLogService extends IService<IotDeviceOperationLog> {
    void recordLog(String deviceId, String deviceCode, String operationType,
                   String operationDesc, String operationDetail, String operationSource,
                   String operationResult, String failReason, String operator,
                   LocalDateTime operationTime);
    IPage<IotDeviceOperationLog> queryLogPage(Page<IotDeviceOperationLog> page,
                                               String deviceId, String operationType,
                                               LocalDateTime startTime, LocalDateTime endTime);
}
