package org.jeecg.modules.device.component;


import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /**
     * 仅保留 anchor 部门及其下级部门节点；不包含上级与其它分支。上层若已不在集合内则 parentId 置空，作为新根参与 {@link #buildEnterpriseTree}。
     */
    public List<OptionTreeDTO> buildEnterpriseTreeForAnchors(List<EnterpriseNodeRowDTO> allRows, Set<String> anchorIds) {
        if (allRows == null || allRows.isEmpty() || anchorIds == null || anchorIds.isEmpty()) {
            return List.of();
        }
        Map<String, EnterpriseNodeRowDTO> byId = new HashMap<>();
        for (EnterpriseNodeRowDTO r : allRows) {
            if (r == null || r.getId() == null || r.getId().isEmpty()) {
                continue;
            }
            byId.put(r.getId(), r);
        }
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (EnterpriseNodeRowDTO r : byId.values()) {
            String p = r.getParentId();
            if (p == null || p.isEmpty()) {
                continue;
            }
            childrenByParent.computeIfAbsent(p, k -> new ArrayList<>()).add(r.getId());
        }
        Set<String> allowed = new HashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        for (String anchor : anchorIds) {
            if (byId.containsKey(anchor)) {
                queue.add(anchor);
            }
        }
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!allowed.add(id)) {
                continue;
            }
            List<String> ch = childrenByParent.get(id);
            if (ch != null) {
                queue.addAll(ch);
            }
        }
        if (allowed.isEmpty()) {
            return List.of();
        }
        List<EnterpriseNodeRowDTO> scoped = new ArrayList<>(allowed.size());
        for (String id : allowed) {
            EnterpriseNodeRowDTO orig = byId.get(id);
            EnterpriseNodeRowDTO copy = new EnterpriseNodeRowDTO();
            copy.setId(orig.getId());
            copy.setName(orig.getName());
            copy.setOrgCategory(orig.getOrgCategory());
            String pid = orig.getParentId();
            if (pid != null && allowed.contains(pid)) {
                copy.setParentId(pid);
            } else {
                copy.setParentId(null);
            }
            scoped.add(copy);
        }
        return buildEnterpriseTree(scoped);
    }
}
