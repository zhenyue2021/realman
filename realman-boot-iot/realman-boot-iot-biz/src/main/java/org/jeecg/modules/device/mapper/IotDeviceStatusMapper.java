package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.entity.IotDeviceStatus;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IotDeviceStatusMapper extends BaseMapper<IotDeviceStatus> {

    /**
     * 查询所有存在历史状态的设备ID
     */
    List<String> selectAllDeviceIds();

    /**
     * 为指定设备删除“今天”内每小时多余的记录，只保留每小时最新一条。
     */
    int deleteTodayHourlyRedundant(@Param("deviceId") String deviceId,
                                   @Param("todayStart") LocalDateTime todayStart);
    /**
     * 为指定设备删除“今天”内、且早于当前小时的每小时多余记录（当前小时内全部保留）。
     */
    int deleteTodayHourlyRedundantBeforeHour(@Param("deviceId") String deviceId,
                                             @Param("todayStart") LocalDateTime todayStart,
                                             @Param("hourCutoff") LocalDateTime hourCutoff);

    /**
     * 为指定设备删除“今天以前、最近7天窗口内”同一天多余的记录，只保留每天最后一条。
     */
    int deleteRecentDailyRedundant(@Param("deviceId") String deviceId,
                                   @Param("startDay") LocalDateTime startDay,
                                   @Param("todayStart") LocalDateTime todayStart);

    /**
     * 查询某设备在7天窗口开始之前最新的一条记录（作为保底，不被清理）。
     */
    IotDeviceStatus selectLatestBefore(@Param("deviceId") String deviceId,
                                       @Param("beforeTime") LocalDateTime beforeTime);

    /**
     * 删除指定设备在 beforeTime 之前的所有记录，排除 keepId。
     */
    int deleteOlderThan(@Param("deviceId") String deviceId,
                        @Param("beforeTime") LocalDateTime beforeTime,
                        @Param("keepId") String keepId);

    /**
     * 将指定设备在 beforeTime 之前的所有记录（排除 keepId）备份到 iot_device_status_history 表中。
     */
    int backupOlderThan(@Param("deviceId") String deviceId,
                        @Param("beforeTime") LocalDateTime beforeTime,
                        @Param("keepId") String keepId);

    /**
     * 删除 iot_device_status_history 表中 beforeTime 之前的所有记录。
     */
    int deleteHistoryOlderThan(@Param("beforeTime") LocalDateTime beforeTime);
}
