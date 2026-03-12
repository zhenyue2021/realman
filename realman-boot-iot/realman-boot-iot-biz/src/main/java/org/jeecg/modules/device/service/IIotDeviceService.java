package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.DeviceUpdateDTO;
import org.jeecg.modules.device.entity.IotDevice;
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
}
