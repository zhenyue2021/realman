package org.jeecg.modules.device.controller;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.subject.Subject;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;
import org.jeecg.modules.device.dto.DeviceAuthDTO;
import org.jeecg.modules.device.dto.DeviceAuthQueryDTO;
import org.jeecg.modules.device.dto.EnterpriseNodeRowDTO;
import org.jeecg.modules.device.dto.OptionDTO;
import org.jeecg.modules.device.dto.OptionTreeDTO;
import org.jeecg.modules.device.entity.IotDeviceAuth;
import org.jeecg.modules.device.feign.SysAuthFeignClient;
import org.jeecg.modules.device.mapper.SysDepartLiteMapper;
import org.jeecg.modules.device.mapper.SysTenantLiteMapper;
import org.jeecg.modules.device.mapper.SysUserRoleLiteMapper;
import org.jeecg.modules.device.service.IIotDeviceAuthService;
import org.jeecg.modules.device.vo.ApiResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 设备授权管理
 */
@Slf4j
@RestController
@RequestMapping("/api/device/auth")
@RequiredArgsConstructor
@Tag(name = "设备授权管理", description = "设备授权增删改查与分页查询")
public class DeviceAuthController {

    private final IIotDeviceAuthService authService;
    private final SysTenantLiteMapper tenantMapper;
    private final SysDepartLiteMapper departMapper;
    private final SysUserRoleLiteMapper userRoleLiteMapper;
    private final SysAuthFeignClient sysAuthFeignClient;

    @PostMapping("/page")
    @Operation(summary = "分页查询设备授权列表")
    public ApiResult<IPage<DeviceAuthDTO>> page(HttpServletRequest request,
                                                @RequestBody DeviceAuthQueryDTO query) {
        int pageNo = query.getPageNo() != null ? query.getPageNo() : 1;
        int pageSize = query.getPageSize() != null ? query.getPageSize() : 20;

        String username = null;
        try {
            username = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        boolean allowed = isSuperAdminOrOps(request, username);
        if (!allowed) {
            return ApiResult.fail("无权限：仅超级管理员/运维人员可访问");
        }

        IPage<IotDeviceAuth> entityPage = authService.queryAuthPage(
                new Page<>(pageNo, pageSize),
                query,
                username,
                true
        );

        Page<DeviceAuthDTO> dtoPage = new Page<>(entityPage.getCurrent(), entityPage.getSize(), entityPage.getTotal());
        List<DeviceAuthDTO> dtoRecords = entityPage.getRecords() == null ? List.of() :
                entityPage.getRecords().stream().map(this::toDto).toList();
        dtoPage.setRecords(dtoRecords);
        return ApiResult.ok(dtoPage);
    }

    @PostMapping("/add")
    @Operation(summary = "新增设备授权")
    public ApiResult<DeviceAuthDTO> create(@RequestBody DeviceAuthDTO dto,
                                           HttpServletRequest request) {
        assertAdminOrOps(request);
        IotDeviceAuth auth = new IotDeviceAuth();
        BeanUtil.copyProperties(dto, auth);
        try {
            String username = JwtUtil.getUserNameByToken(request);
            auth.setCreateBy(username);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        authService.save(auth);
        return ApiResult.ok(toDto(auth));
    }

    @PutMapping("/{id}")
    @Operation(summary = "修改设备授权")
    public ApiResult<DeviceAuthDTO> update(@PathVariable String id,
                                           @RequestBody DeviceAuthDTO dto,
                                           HttpServletRequest request) {
        assertAdminOrOps(request);
        IotDeviceAuth auth = new IotDeviceAuth();
        BeanUtil.copyProperties(dto, auth);
        auth.setId(id);
        try {
            String username = JwtUtil.getUserNameByToken(request);
            auth.setUpdateBy(username);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        authService.updateById(auth);
        return ApiResult.ok(toDto(auth));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除设备授权（逻辑删除）")
    public ApiResult<Void> delete(@PathVariable String id, HttpServletRequest request) {
        assertAdminOrOps(request);
        authService.removeById(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/options/tenants")
    @Operation(summary = "租户下拉列表")
    public ApiResult<List<OptionDTO>> tenantOptions(HttpServletRequest request) {
        assertAdminOrOps(request);
        return ApiResult.ok(tenantMapper.listAllTenants());
    }


    @GetMapping("/options/enterprises/tree")
    @Operation(summary = "企业下拉树")
    public ApiResult<List<OptionTreeDTO>> enterpriseOptionsTree(HttpServletRequest request) {
        assertAdminOrOps(request);
        List<EnterpriseNodeRowDTO> rows = departMapper.listEnterpriseTreeRows();
        return ApiResult.ok(buildEnterpriseTree(rows));
    }

    @PostMapping("/export")
    @Operation(summary = "导出授权列表Excel")
    public ResponseEntity<byte[]> export(HttpServletRequest request, @RequestBody DeviceAuthQueryDTO query) {
        assertAdminOrOps(request);
        String username = null;
        try {
            username = JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException e) {
            log.warn("获取登录用户失败: {}", e.getMessage());
        }
        boolean superAdmin = "admin".equalsIgnoreCase(username);
        byte[] bytes = authService.exportAuthList(query, username, superAdmin);
        String filename = "device_auth_" + System.currentTimeMillis() + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + URLEncoder.encode(filename, StandardCharsets.UTF_8))
                .body(bytes);
    }

    private DeviceAuthDTO toDto(IotDeviceAuth auth) {
        if (auth == null) {
            return null;
        }
        DeviceAuthDTO dto = new DeviceAuthDTO();
        BeanUtil.copyProperties(auth, dto);
        return dto;
    }

    private void assertAdminOrOps(HttpServletRequest request) {
        String username = null;
        if (request != null) {
            try {
                username = JwtUtil.getUserNameByToken(request);
            } catch (JeecgBootException e) {
                log.warn("获取登录用户失败: {}", e.getMessage());
            }
        }
        if (!(isSuperAdminOrOps(request, username))) {
            throw new RuntimeException("无权限：仅超级管理员/运维人员可访问");
        }
    }


    private boolean isSuperAdminOrOps(HttpServletRequest request, String usernameFromToken) {
        try {
            Subject subject = SecurityUtils.getSubject();
            Set<String> roles = resolveRoleCodes(subject, request, usernameFromToken);
            return roles.contains("admin")
                    || roles.contains("yunwei");
        } catch (Exception e) {
            log.warn("判断运维角色失败: {}", e.getMessage());
            return false;
        }
    }

    private Set<String> resolveRoleCodes(Subject subject, HttpServletRequest request, String usernameFromToken) {
        Object principal = subject == null ? null : subject.getPrincipal();

        if (principal instanceof org.jeecg.common.system.vo.LoginUser u) {
            String roleCodeStr = u.getRoleCode();
            Set<String> roles = splitRoleCodes(roleCodeStr);
            log.info("[DeviceAuth] roleSource=shiroPrincipal, username={}, roleCode={}, roles={}",
                    u.getUsername(), roleCodeStr, roles);
            return roles;
        }

        String username = usernameFromToken;
        if ((username == null || username.isBlank()) && request != null) {
            try {
                username = JwtUtil.getUserNameByToken(request);
            } catch (Exception ignored) {
                // ignore
            }
        }

        if (username == null || username.isBlank()) {
            log.info("[DeviceAuth] roleSource=none, subjectAuthenticated={}, principals=null",
                    subject != null && subject.isAuthenticated());
            return Collections.emptySet();
        }

        // 彻底解决 401：显式携带当前请求 token 调 system（不依赖 RequestContextHolder 透传）
        String token = request == null ? null : request.getHeader(CommonConstant.X_ACCESS_TOKEN);
        if (token != null && !token.isBlank()) {
            try {
                Set<String> roles = sysAuthFeignClient.queryUserRoles(token, username);
                log.info("[DeviceAuth] roleSource=sysAuthFeign, username={}, roles={}", username, roles);
                return roles == null ? Collections.emptySet() : roles;
            } catch (Exception e) {
                log.warn("[DeviceAuth] sysAuthFeign 查询用户角色失败 username={}, err={}，fallback=localDb", username, e.getMessage());
            }
        } else {
            log.info("[DeviceAuth] 缺少请求头 {}，fallback=localDb", CommonConstant.X_ACCESS_TOKEN);
        }

        // fallback：本地DB查角色（保证业务不被外部依赖阻断）
        try {
            List<String> roleCodes = userRoleLiteMapper.listRoleCodesByUsername(username);
            Set<String> roles = roleCodes == null ? Collections.emptySet() : roleCodes.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .collect(java.util.stream.Collectors.toSet());
            log.info("[DeviceAuth] roleSource=localDb, username={}, roles={}", username, roles);
            return roles;
        } catch (Exception ex) {
            log.warn("[DeviceAuth] 本地DB查询用户角色失败 username={}, err={}", username, ex.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<String> splitRoleCodes(String roleCodeStr) {
        if (roleCodeStr == null || roleCodeStr.isBlank()) {
            return Collections.emptySet();
        }
        return Arrays.stream(roleCodeStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toSet());
    }

    private List<OptionTreeDTO> buildEnterpriseTree(List<EnterpriseNodeRowDTO> rows) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }

        Map<String, OptionTreeDTO> nodeById = new HashMap<>();
        Map<String, String> parentById = new HashMap<>();
        List<String> roots = new ArrayList<>();

        for (EnterpriseNodeRowDTO r : rows) {
            if (r == null || r.getId() == null || r.getId().isEmpty()) {
                continue;
            }
            OptionTreeDTO node = new OptionTreeDTO(r.getId(), r.getName());
            nodeById.put(r.getId(), node);
            parentById.put(r.getId(), r.getParentId());
            if ("1".equals(r.getOrgCategory())) {
                roots.add(r.getId());
            }
        }

        // 把子公司挂到 parent 下（若 parent 不在返回集合里，则降级为根）
        for (EnterpriseNodeRowDTO r : rows) {
            if (r == null || r.getId() == null || r.getId().isEmpty()) {
                continue;
            }
            if (!"4".equals(r.getOrgCategory())) {
                continue;
            }
            String parentId = r.getParentId();
            OptionTreeDTO parent = parentId == null ? null : nodeById.get(parentId);
            OptionTreeDTO child = nodeById.get(r.getId());
            if (child == null) {
                continue;
            }
            if (parent != null) {
                parent.getChildren().add(child);
            } else {
                // 找不到 parent，避免丢数据：作为根返回
                roots.add(r.getId());
            }
        }

        List<OptionTreeDTO> result = new ArrayList<>();
        for (String id : roots) {
            OptionTreeDTO n = nodeById.get(id);
            if (n != null) {
                result.add(n);
            }
        }
        return result;
    }
}

