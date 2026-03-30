package org.jeecg.modules.device.component;


import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class DeviceServiceComponent {

    public List<OptionTreeDTO> buildEnterpriseTree(List<EnterpriseNodeRowDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<String, OptionTreeDTO> nodeById = new HashMap<>();
        List<String> roots = new ArrayList<>();

        for (EnterpriseNodeRowDTO r : rows) {
            if (r == null || r.getId() == null || r.getId().isEmpty()) {
                continue;
            }
            OptionTreeDTO node = new OptionTreeDTO(r.getId(), r.getName());
            nodeById.put(r.getId(), node);
            if ("1".equals(r.getOrgCategory())) {
                roots.add(r.getId());
            }
        }

        for (EnterpriseNodeRowDTO r : rows) {
            if (r == null || r.getId() == null || r.getId().isEmpty()) continue;
            if (!"4".equals(r.getOrgCategory())) continue;
            OptionTreeDTO parent = r.getParentId() == null ? null : nodeById.get(r.getParentId());
            OptionTreeDTO child = nodeById.get(r.getId());
            if (child == null) continue;
            if (parent != null) parent.getChildren().add(child);
            else roots.add(r.getId());
        }

        List<OptionTreeDTO> result = new ArrayList<>();
        for (String id : roots) {
            OptionTreeDTO n = nodeById.get(id);
            if (n != null) result.add(n);
        }
        return result;
    }
}
