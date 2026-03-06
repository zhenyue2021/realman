package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;
import org.jeecg.modules.device.entity.IotOtaUpgradeTask;
@Mapper
public interface IotOtaUpgradeTaskMapper extends BaseMapper<IotOtaUpgradeTask> {
    @Update("UPDATE iot_ota_upgrade_task t SET " +
        "t.success_count=(SELECT COUNT(*) FROM iot_ota_upgrade_record WHERE task_id=#{taskId} AND upgrade_status=6)," +
        "t.fail_count=(SELECT COUNT(*) FROM iot_ota_upgrade_record WHERE task_id=#{taskId} AND upgrade_status IN(7,8))," +
        "t.upgrading_count=(SELECT COUNT(*) FROM iot_ota_upgrade_record WHERE task_id=#{taskId} AND upgrade_status IN(1,2,3,4,5)) " +
        "WHERE t.id=#{taskId}")
    void refreshTaskStatistics(@Param("taskId") String taskId);
}
