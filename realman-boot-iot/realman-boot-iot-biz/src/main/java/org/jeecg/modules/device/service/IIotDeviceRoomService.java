package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotDeviceRoom;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.vo.DeviceRoomVO;

import java.util.List;

/**
 * IoT 设备房间服务
 *
 * <p>房间生命周期：
 * <ol>
 *   <li>主控 {@link #queryOrCreate} 时传入 robotCode，创建/复用房间并绑定双端</li>
 *   <li>停止遥操或设备离线时调用 {@link #destroyRoom} / {@link #destroyRoomByRobotCode}，销毁房间并清理缓存</li>
 * </ol>
 */
public interface IIotDeviceRoomService extends IService<IotDeviceRoom> {

    /**
     * 查询或创建主控房间，同时绑定机器人，并返回动态调度后的 WebRTC 参数。
     *
     * @param masterCode 主控设备编码
     * @param robotCode  机器人设备编码
     * @return WebRTC 连接参数（含 roomId、信令/TURN/STUN）
     */
    MqttMessageModel.WebRtcCommand queryOrCreate(String masterCode, String robotCode);

    /**
     * 通过主控编码销毁房间（停止遥操 / 主控设备离线时调用）
     *
     * @param masterCode 主控设备编码
     */
    void destroyRoom(String masterCode);

    /**
     * 通过机器人编码销毁房间（机器人设备离线时调用）
     *
     * @param robotCode 机器人设备编码
     */
    void destroyRoomByRobotCode(String robotCode);

    /**
     * 查询所有活跃房间（缓存优先，降级走 DB）
     *
     * @return 活跃房间列表
     */
    List<DeviceRoomVO> listActiveRooms();
}
