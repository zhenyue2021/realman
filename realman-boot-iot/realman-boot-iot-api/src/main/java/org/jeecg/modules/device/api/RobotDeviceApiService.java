package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.jeecg.modules.device.dto.DeviceRequestDTO;
import org.jeecg.modules.device.dto.RobotDevicePageItemDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.vo.RobotDeviceDetailVO;

public interface RobotDeviceApiService {

    /**
     * 机器人设备分页列表（device_type=1），返回前端 DTO，不直出实体
     */
    IPage<RobotDevicePageItemDTO> pageRobots(Page<?> page, DeviceRequestDTO requestDTO);

    /**
     * 按主键查询机器人设备简要信息，非机器人或不存在则抛异常
     */
    RobotDevicePageItemDTO getRobotDeviceView(String deviceId);

    /**
     * 机器人设备详情聚合（配置、状态、日志等均为视图 DTO）
     */
    RobotDeviceDetailVO getRobotDeviceDetailAgg(String deviceId);

    /**
     * 将已持久化的机器人实体转为列表/详情用 DTO（过滤敏感字段）
     */
    RobotDevicePageItemDTO toPageItem(IotDevice device);
}
