package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IotDeviceConfigMapper extends BaseMapper<IotDeviceConfig> {
    int updateSyncStatusByDeviceCode(@Param("deviceCode") String deviceCode,
        @Param("syncStatus") int syncStatus, @Param("syncTime") LocalDateTime syncTime);

    /**
     * 批量 upsert：INSERT INTO ... ON DUPLICATE KEY UPDATE
     * 依赖唯一索引 uk_device_config(device_id, config_key)
     */
    int batchUpsert(@Param("list") List<IotDeviceConfig> list);
}
