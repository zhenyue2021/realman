package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.dto.MasterControlParamsDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.vo.DeviceCameraStreamVO;
import org.jeecg.modules.device.vo.DeviceDetailVO;

import java.util.List;
import java.util.Map;

public interface IIotDeviceService extends IService<IotDevice> {
    IotDevice addDevice(IotDevice device);

    void updateDevice(String deviceId, DeviceUpdateDTO dto);

    IPage<IotDevice> queryDevicePage(Page<IotDevice> page, DeviceRequestDTO requestDTO);

    void setAndSyncConfig(String deviceId, Map<String, Object> params);

    Map<String, Object> getDeviceMonitorStatus(String deviceId);

    DeviceDetailVO getDeviceDetail(String deviceId);

    /**
     * 发送通用指令：下行 Topic 统一为 device/{deviceCode}/command/{cmd}
     *
     * @return 生成的 commandId（用于关联上行 ACK）
     */
    String sendCommand(String deviceId, String cmd, String reason, String operator);

    void remoteRestart(String deviceId, String reason, String operator);

    void emergencyStop(String deviceId, String reason, String operator);

    /**
     * 开始遥操：通知主控关联目标机器人，不等待ACK。
     *
     * @return 全部摄像头流信息列表
     */
    List<DeviceCameraStreamVO> startTeleop(String controllerId, String robotId, String operator);

    void startTeleopNoStream(String controllerId, String robotId, String operator);

    /**
     * 停止遥操：通知主控与机器人停止遥操，不等待ACK。
     */
    void stopTeleop(String controllerId, String robotId, String robotCode, String operator);

    void changeDeviceStatus(String deviceId, Integer status, String operator);


    List<Map<String, Object>> batchGetOnlineStatus(List<String> deviceIds);

    /**
     * 导出设备列表为 Excel（条件与 list 一致，受数据权限控制，最多 {@link org.jeecg.modules.device.util.DeviceExcelExportUtil#getMaxExportRows()} 条）
     */
    byte[] exportDeviceList(DeviceRequestDTO requestDTO);

    /**
     * 向机器人查询摄像头视频流地址（同步等待，最长 10 秒）
     *
     * @param deviceId    设备 ID
     * @param cameraIndex 指定摄像头路数索引，null 表示查询全部，非 null 时必须为非负整数
     * @return 摄像头流信息列表（cameraIndex 为空时为全部，非空时通常只包含单路）
     */
    List<DeviceCameraStreamVO> getCameraStreams(String deviceId, Integer cameraIndex);

    /**
     * 向设备下发力反馈查询指令（值置为 null 表示查询），不等待响应
     */
    void queryMasterForceFeedback(String robotId);

    /**
     * 向主控下发运动速度查询指令（值置为 null 表示查询），不等待响应
     */
    void queryMasterSportSpeed(String controllerId);

    /**
     * 一次性下发力反馈与运动/安全参数（内部依次下发 MQTT，不等待 ACK）。
     *
     * @param controller 已校验为主控类型的设备实体
     * @param dto        力反馈与运动参数（与 {@code /control-params} 请求体一致）
     */
    void applyMasterControlParams(IotDevice controller, MasterControlParamsDTO dto);

    /**
     * 查询设备授权信息
     * @param deviceIds
     * @param tenantId
     * @param deviceTyp
     * @return
     */
    Map<String, IotDeviceAuth> loadTenantAuth(List<String> deviceIds, String tenantId, String deviceTyp);
}
