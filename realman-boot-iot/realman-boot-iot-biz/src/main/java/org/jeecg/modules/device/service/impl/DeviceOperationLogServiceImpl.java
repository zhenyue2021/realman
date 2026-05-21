package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotDeviceOperationLog;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotDeviceOperationLogMapper;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeviceOperationLogServiceImpl
        extends ServiceImpl<IotDeviceOperationLogMapper, IotDeviceOperationLog>
        implements IDeviceOperationLogService {

    private final IotDeviceMapper deviceMapper;

    @Override
    @Async("devicePersistExecutor")
    public void recordLog(String deviceId, String deviceCode, String operationType,
                          String operationDesc, String operationDetail, String operationSource,
                          String operationResult, String failReason, String operator,
                          LocalDateTime operationTime) {
        try {
            // 若deviceId为空，尝试通过deviceCode查询
            if (deviceId == null && deviceCode != null) {
                var device = deviceMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<>(
                        org.jeecg.modules.device.entity.IotDevice.class)
                        .eq(org.jeecg.modules.device.entity.IotDevice::getDeviceCode, deviceCode)
                        .select(org.jeecg.modules.device.entity.IotDevice::getId));
                if (device != null) deviceId = device.getId();
            }
            IotDeviceOperationLog log2 = new IotDeviceOperationLog();
            log2.setDeviceId(deviceId);
            log2.setDeviceCode(deviceCode);
            log2.setOperationType(operationType);
            log2.setOperationDesc(operationDesc);
            log2.setOperationDetail(operationDetail);
            log2.setOperationSource(operationSource);
            log2.setOperationResult(operationResult != null ? operationResult : "SUCCESS");
            log2.setFailReason(failReason);
            log2.setOperator(operator);
            log2.setCreateTime(LocalDateTime.now());
            log2.setOperationTime(operationTime != null ? operationTime : LocalDateTime.now());
            save(log2);
        } catch (Exception e) {
            log.error("[OpLog] 记录日志失败 deviceCode={}", deviceCode, e);
        }
    }

    @Override
    public IPage<IotDeviceOperationLog> queryLogPage(Page<IotDeviceOperationLog> page,
                                                      String deviceId, String operationType,
                                                      LocalDateTime startTime, LocalDateTime endTime) {
        return page(page, new LambdaQueryWrapper<IotDeviceOperationLog>()
                .eq(deviceId != null,     IotDeviceOperationLog::getDeviceId,      deviceId)
                .eq(operationType != null, IotDeviceOperationLog::getOperationType, operationType)
                .ge(startTime != null,    IotDeviceOperationLog::getOperationTime,  startTime)
                .le(endTime != null,      IotDeviceOperationLog::getOperationTime,  endTime)
                .orderByDesc(IotDeviceOperationLog::getOperationTime));
    }
}
