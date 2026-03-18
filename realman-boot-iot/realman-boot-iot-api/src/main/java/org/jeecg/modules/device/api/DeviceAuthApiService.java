package org.jeecg.modules.device.api;

import com.baomidou.mybatisplus.core.metadata.IPage;
import org.jeecg.modules.device.dto.DeviceAuthDTO;
import org.jeecg.modules.device.dto.DeviceAuthDetailDTO;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
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

    List<OptionDTO> availableDevices(HttpServletRequest request, Integer deviceType);

    ResponseEntity<byte[]> export(HttpServletRequest request, DeviceAuthQueryDTO query);
}

