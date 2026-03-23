package org.jeecg.modules.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.entity.IotDevice;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface IotDeviceMapper extends BaseMapper<IotDevice> {
    IPage<IotDevice> selectDeviceList(Page<IotDevice> page,
        @Param("deviceName") String deviceName,
        @Param("deviceType") Integer deviceType,
        @Param("status")     Integer status,
        @Param("productId")  String productId,
        @Param("authEffectiveTime") LocalDateTime authEffectiveTime,
        @Param("authExpireTime") LocalDateTime authExpireTime,
        @Param("currentUsername") String currentUsername,
        @Param("currentTenantId") String currentTenantId,
        @Param("superAdmin")      Boolean superAdmin);

    /**
     * 可授权设备列表：
     * - deviceType=2：主控（用 iot_device_auth.controller_id 判断是否已授权）
     * - deviceType=1：机器人（用 iot_device_auth.device_id 判断是否已授权）
     *
     * 规则：只返回 iot_device_auth 中不存在 del_flag=0 记录的设备（允许历史 del_flag=1）。
     */
    @Select("""
            <script>
            SELECT d.id AS id,
                   d.device_code AS name
            FROM iot_device d
            WHERE d.del_flag = 0
              AND d.device_type = #{deviceType}
              AND NOT EXISTS (
                SELECT 1 FROM iot_device_auth a
                WHERE a.del_flag = 0
                <choose>
                  <when test="deviceType != null and deviceType == 2">
                    AND a.controller_id = d.id
                  </when>
                  <otherwise>
                    AND a.device_id = d.id
                  </otherwise>
                </choose>
              )
            ORDER BY d.device_code ASC
            </script>
            """)
    List<OptionDTO> listAvailableDevices(@Param("deviceType") Integer deviceType);

    /**
     * 授权记录查询条件下拉：返回指定类型的全部未删除设备（含已有授权记录的设备）。
     * deviceType：1 机器人，2 主控。
     */
    @Select("""
            SELECT d.id AS id,
                   d.device_code AS name
            FROM iot_device d
            WHERE d.del_flag = 0
              AND d.device_type = #{deviceType}
            ORDER BY d.device_code ASC
            """)
    List<OptionDTO> listDevicesForAuthQuery(@Param("deviceType") Integer deviceType);

    @Select("""
            SELECT d.admin_user_id AS id,
                   d.admin_username AS name
            FROM iot_device_auth d
            WHERE d.del_flag = 0
            ORDER BY d.create_time ASC
            """)
    List<OptionDTO> listAuthUsers();
}
