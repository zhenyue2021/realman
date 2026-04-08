package org.jeecg.modules.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.entity.IotDeviceRoom;

/**
 * IoT 设备房间 Mapper
 *
 * <p>"活跃房间" 定义：status IN (0, 1) AND del_flag = 0
 */
@Mapper
public interface IotDeviceRoomMapper extends BaseMapper<IotDeviceRoom> {

    /**
     * 按主控编码查询活跃房间（最新一条）
     */
    @Select("SELECT * FROM iot_device_room " +
            "WHERE master_code = #{masterCode} AND status IN (0, 1) AND del_flag = 0 " +
            "ORDER BY create_time DESC LIMIT 1")
    IotDeviceRoom selectActiveByMasterCode(@Param("masterCode") String masterCode);

    /**
     * 按机器人编码查询活跃房间（最新一条）
     */
    @Select("SELECT * FROM iot_device_room " +
            "WHERE robot_code = #{robotCode} AND status IN (0, 1) AND del_flag = 0 " +
            "ORDER BY create_time DESC LIMIT 1")
    IotDeviceRoom selectActiveByRobotCode(@Param("robotCode") String robotCode);
}
