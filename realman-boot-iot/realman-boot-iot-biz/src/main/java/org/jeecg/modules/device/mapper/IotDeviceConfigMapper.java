package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import java.time.LocalDateTime;

@Mapper
public interface IotDeviceConfigMapper extends BaseMapper<IotDeviceConfig> {
    int updateSyncStatusByDeviceCode(@Param("deviceCode") String deviceCode,
        @Param("syncStatus") int syncStatus, @Param("syncTime") LocalDateTime syncTime);
}
