package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.device.entity.IotDeviceRoom;
import org.jeecg.modules.device.vo.DeviceRoomVO;

import java.util.List;

/**
 * IoT 设备房间服务
 *
 * <p>房间生命周期由三个外部事件驱动：
 * <ol>
 *   <li>主控查询服务地址时调用 {@link #queryOrCreate}，房间不存在则自动创建</li>
 *   <li>开始遥操时调用 {@link #robotJoin}，将机器人加入房间</li>
 *   <li>停止遥操或设备离线时调用 {@link #destroyRoom} / {@link #destroyRoomByRobotCode}，销毁房间并清理缓存</li>
 * </ol>
 */
public interface IIotDeviceRoomService extends IService<IotDeviceRoom> {

    /**
     * 查询主控的活跃房间，不存在则创建（缓存优先）
     *
     * @param masterCode 主控设备编码
     * @return 房间信息
     */
    DeviceRoomVO queryOrCreate(String masterCode);

    /**
     * 机器人加入房间（遥操开始时调用）
     *
     * <p>更新 DB robot_code + status=ACTIVE，刷新缓存，建立机器人反查索引。
     *
     * @param masterCode 主控设备编码
     * @param robotCode  机器人设备编码
     */
    void robotJoin(String masterCode, String robotCode);

    /**
     * 通过主控编码销毁房间（停止遥操 / 主控设备离线时调用）
     *
     * <p>更新 DB status=DESTROYED + del_flag=1，清理全部缓存 Key。
     *
     * @param masterCode 主控设备编码
     */
    void destroyRoom(String masterCode);

    /**
     * 通过机器人编码销毁房间（机器人设备离线时调用）
     *
     * <p>先通过缓存反查 masterCode，再委托 {@link #destroyRoom}；缓存未命中则降级查 DB。
     *
     * @param robotCode 机器人设备编码
     */
    void destroyRoomByRobotCode(String robotCode);

    /**
     * 查询所有活跃房间（缓存优先，降级走 DB）
     *
     * @return 活跃房间列表（含 WAITING 和 ACTIVE 状态）
     */
    List<DeviceRoomVO> listActiveRooms();
}
