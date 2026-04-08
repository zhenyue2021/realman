package org.jeecg.modules.device.service.impl;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.dto.MasterControlParamsDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.IIotDeviceService;
import org.jeecg.modules.device.service.impl.device.*;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceDetailVO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 设备管理门面：委托各子域 Service，保持 {@link IIotDeviceService} 契约不变。
 * 所有事务边界在此类统一声明，子域 Service 不重复标注 {@code @Transactional}。
 *
 * @see org.jeecg.modules.device.service.impl.device 子域实现
 */
@Slf4j
@Service
public class IotDeviceServiceImpl extends ServiceImpl<IotDeviceMapper, IotDevice>
        implements IIotDeviceService {

    private final IotDeviceLifecycleService lifecycleService;
    private final IotDeviceConfigSyncService configSyncService;
    private final IotDeviceStatusQueryService statusQueryService;
    private final IotDeviceCameraStreamService cameraStreamService;
    private final IotDeviceMqttCommandService mqttCommandService;
    private final IotDeviceTeleopService teleopService;
    private final IotDeviceSupport iotDeviceSupport;

    public IotDeviceServiceImpl(IotDeviceMapper deviceMapper,
                                IotDeviceLifecycleService lifecycleService,
                                IotDeviceConfigSyncService configSyncService,
                                IotDeviceStatusQueryService statusQueryService,
                                IotDeviceCameraStreamService cameraStreamService,
                                IotDeviceMqttCommandService mqttCommandService,
                                IotDeviceTeleopService teleopService,
                                IotDeviceSupport iotDeviceSupport) {
        this.baseMapper = deviceMapper;
        this.lifecycleService = lifecycleService;
        this.configSyncService = configSyncService;
        this.statusQueryService = statusQueryService;
        this.cameraStreamService = cameraStreamService;
        this.mqttCommandService = mqttCommandService;
        this.teleopService = teleopService;
        this.iotDeviceSupport = iotDeviceSupport;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public IotDevice addDevice(IotDevice device) {
        return lifecycleService.addDevice(device);
    }

    @Override
    public IPage<IotDevice> queryDevicePage(Page<IotDevice> page, DeviceRequestDTO request) {
        return lifecycleService.queryDevicePage(page, request);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDevice(String deviceId, DeviceUpdateDTO dto) {
        lifecycleService.updateDevice(deviceId, dto);
    }

    @Override
    public byte[] exportDeviceList(DeviceRequestDTO requestDTO) {
        return lifecycleService.exportDeviceList(requestDTO);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setAndSyncConfig(String deviceId, Map<String, Object> params) {
        configSyncService.setAndSyncConfig(deviceId, params);
    }

    @Override
    public Map<String, Object> getDeviceMonitorStatus(String deviceId) {
        return statusQueryService.getDeviceMonitorStatus(deviceId);
    }

    @Override
    public DeviceDetailVO getDeviceDetail(String deviceId) {
        return statusQueryService.getDeviceDetail(deviceId);
    }

    @Override
    public String sendCommand(String deviceId, String cmd, String reason, String operator) {
        return mqttCommandService.sendCommand(deviceId, cmd, reason, operator);
    }

    @Override
    public void remoteRestart(String deviceId, String reason, String operator) {
        mqttCommandService.remoteRestart(deviceId, reason, operator);
    }

    @Override
    public void emergencyStop(String deviceId, String reason, String operator) {
        mqttCommandService.emergencyStop(deviceId, reason, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<DeviceCameraStreamVO> startTeleop(String controllerId, String robotId, String operator) {
        return teleopService.startTeleop(controllerId, robotId, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void startTeleopNoStream(String controllerId, String robotId, String operator) {
        teleopService.startTeleopNoStream(controllerId, robotId, operator);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void stopTeleop(String controllerId, String robotId, String robotCode, String operator) {
        teleopService.stopTeleop(controllerId, robotId, robotCode, operator);
    }

    /**
     * 主控参数下发：先发 MQTT 指令，成功后持久化配置。
     * 两步操作在同一事务内，MQTT 发送异常会回滚 DB 写入。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void applyMasterControlParams(IotDevice controller, MasterControlParamsDTO dto) {
        mqttCommandService.applyMasterControlParams(controller, dto);
        configSyncService.upsertDeviceConfig(controller.getId(), controller.getDeviceCode(),
                "arm_level",
                dto.getArmLevel() == null ? null : dto.getArmLevel().toString(),
                Objects.nonNull(dto.getArmLevelConfigType()) ? dto.getArmLevelConfigType() : "0");
        configSyncService.upsertDeviceConfig(controller.getId(), controller.getDeviceCode(),
                "move_speed_level",
                dto.getMoveSpeedLevel() == null ? null : dto.getMoveSpeedLevel().toString(),
                Objects.nonNull(dto.getMoveSpeedLevelConfigType()) ? dto.getMoveSpeedLevelConfigType() : "0");
    }

    @Override
    public void changeDeviceStatus(String deviceId, Integer status, String operator) {
        lifecycleService.changeDeviceStatus(deviceId, status, operator);
    }

    @Override
    public List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds) {
        return statusQueryService.batchGetOnlineStatus(deviceIds);
    }

    @Override
    public List<DeviceCameraStreamVO> getCameraStreams(String deviceId, Integer cameraIndex) {
        return cameraStreamService.getCameraStreams(deviceId, cameraIndex);
    }

    @Override
    public void queryMasterForceFeedback(String deviceId) {
        mqttCommandService.queryMasterForceFeedback(deviceId);
    }

    @Override
    public void queryMasterSportSpeed(String controllerId) {
        mqttCommandService.queryMasterSportSpeed(controllerId);
    }

    @Override
    public Map<String, IotDeviceAuth> loadTenantAuth(List<String> deviceIds, String tenantId, String deviceType) {
        return lifecycleService.loadTenantAuth(deviceIds, tenantId, deviceType);
    }
}
