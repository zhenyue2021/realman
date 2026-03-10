package org.jeecg.modules.device.mapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.jeecg.modules.device.entity.IotDevice;

@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDevice> {
    IPage<IotDevice> selectDeviceList(Page<IotDevice> page,
        @Param("deviceName") String deviceName,
        @Param("deviceType") Integer deviceType,
        @Param("status")     Integer status,
        @Param("productId")  String productId,
        @Param("startTime")  java.time.LocalDateTime startTime,
        @Param("endTime")    java.time.LocalDateTime endTime,
        @Param("currentUsername") String currentUsername,
        @Param("currentTenantId") String currentTenantId,
        @Param("superAdmin")      Boolean superAdmin);
}
