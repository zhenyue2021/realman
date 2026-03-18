package org.jeecg.modules.device.feign;

import org.jeecg.common.constant.CommonConstant;
import org.jeecg.common.constant.ServiceNameConstants;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Set;

/**
 * system 侧鉴权相关接口（显式传 token，避免线程上下文丢失导致 401）
 */
@FeignClient(
        contextId = "sysAuthFeignClient",
        value = ServiceNameConstants.SERVICE_SYSTEM,
        path = "${realman.system.context-path:/realman-boot}"
)
public interface SysAuthFeignClient {

    @GetMapping("/sys/api/queryUserRoles")
    Set<String> queryUserRoles(@RequestHeader(CommonConstant.X_ACCESS_TOKEN) String token,
                               @RequestParam("username") String username);
}

