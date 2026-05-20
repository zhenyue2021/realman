package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.device.dto.*;
import org.springframework.http.ResponseEntity;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;

public interface DeviceAuthApiService {

    IPage<DeviceAuthDTO> page(HttpServletRequest request, DeviceAuthQueryDTO query);

    DeviceAuthDTO create(HttpServletRequest request, DeviceAuthDTO dto);

    DeviceAuthDTO update(HttpServletRequest request, String id, DeviceAuthDTO dto);

    void delete(HttpServletRequest request, String id);

    DeviceAuthDetailDTO detail(HttpServletRequest request, String id);

    List<OptionDTO> tenantOptions(HttpServletRequest request);

    List<OptionDTO> tenantUserOptions(HttpServletRequest request, Integer tenantId);

    List<OptionDTO> enterpriseUserOptions(HttpServletRequest request, String enterpriseId);

    List<OptionTreeDTO> enterpriseOptionsTree(HttpServletRequest request);

    List<DeviceOptionDTO> availableDevices(HttpServletRequest request, Integer deviceType);

    /** 授权记录查询：机器人设备下拉（全部未删除机器人，非「仅可授权」） */
    List<OptionDTO> authQueryRobotOptions(HttpServletRequest request);

    /** 授权记录查询：主控设备下拉（全部未删除主控，非「仅可授权」） */
    List<OptionDTO> authQueryControllerOptions(HttpServletRequest request);

    ResponseEntity<byte[]> export(HttpServletRequest request, DeviceAuthQueryDTO query);

    List<OptionDTO> authQueryAuthUsers(HttpServletRequest request);
}

