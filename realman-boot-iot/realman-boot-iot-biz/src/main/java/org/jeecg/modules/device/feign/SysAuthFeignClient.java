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
 * system 模块内部查询接口（Feign）。对应 {@code SystemApiController} 上已标注 {@code @IgnoreAuth} 的方法：
 * 不要求登录 Token、不要求 X-Sign 验签（且未列入 {@code jeecg.signUrls}）。
 * 仍可能透传租户等头（见 {@code FeignHeaderForwardConfig}）；生产环境请配合内网/集群网络隔离。
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

    /** 查询用户角色 code 集合（经 SysBaseApi，与 queryUserRoles 入口不同） */
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

    /** 根据租户ID查询租户名称，不存在时返回空字符串 */
    @GetMapping("/sys/api/getTenantNameById")
    String getTenantNameById(@RequestParam("tenantId") String tenantId);
}
