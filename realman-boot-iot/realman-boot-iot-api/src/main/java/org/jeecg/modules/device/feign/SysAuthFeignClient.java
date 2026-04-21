package org.jeecg.modules.device.feign;

import com.alibaba.fastjson.JSONObject;
import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.constant.ServiceNameConstants;
import org.jeecg.common.system.vo.DictModel;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
import java.util.Set;

/**
 * system 侧对外接口 Feign 客户端。
 * Token 由 FeignHeaderForwardConfig 拦截器透传，无需显式传参。
 * 仅 queryUserRoles 保留显式 token，用于定时任务/异步线程等无 HTTP 上下文的场景。
 */
@FeignClient(
        contextId = "sysAuthFeignClient",
        value = ServiceNameConstants.SERVICE_SYSTEM,
        path = "${realman.system.context-path:/realman-boot}"
)
public interface SysAuthFeignClient {

    /** 查询用户角色 code 集合（保留显式 token，兼容无 HTTP 上下文的异步调用） */
    @GetMapping("/sys/api/queryUserRoles")
    Set<String> queryUserRoles(@RequestHeader(CommonConstant.X_ACCESS_TOKEN) String token,
                               @RequestParam("username") String username);

    /** 查询用户角色 code 集合（有 HTTP 上下文时使用，token 由拦截器自动注入） */
    @GetMapping("/sys/api/getUserRoleSet")
    Set<String> getUserRoleSet(@RequestParam("username") String username);

    /** 查询用户所属部门 ID 列表 */
    @GetMapping("/sys/api/getDepartIdsByUserId")
    List<String> getDepartIdsByUserId(@RequestParam("userId") String userId);

    /** 查询企业/子公司树节点（orgCategory IN '1','4'，状态正常） */
    @GetMapping("/sys/api/listEnterpriseTreeRows")
    List<JSONObject> listEnterpriseTreeRows();

    /** 查询所有有效租户列表（value=id, text=name） */
    @GetMapping("/sys/api/listActiveTenants")
    List<DictModel> listActiveTenants();

    /** 根据用户名查询其所属有效企业 ID 列表（del_flag='0' AND status='1'） */
    @GetMapping("/sys/api/listValidEnterpriseIdsByUsername")
    List<String> listValidEnterpriseIdsByUsername(@RequestParam("username") String username);

    /** 查询指定部门下用户列表（value=userId, text=realname） */
    @GetMapping("/sys/api/listUserOptionsByDepartId")
    List<DictModel> listUserOptionsByDepartId(@RequestParam("departId") String departId);

    /** 查询指定租户下用户列表（value=userId, text=username） */
    @GetMapping("/sys/api/listUserOptionsByTenantId")
    List<DictModel> listUserOptionsByTenantId(@RequestParam("tenantId") Integer tenantId);
}

