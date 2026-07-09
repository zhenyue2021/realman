package org.jeecg.modules.devicemgmt.util;

import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;

/**
 * Controller 层 HTTP 请求工具方法，镜像 {@code realman-boot-iot} 的同名工具类。
 */
public final class RequestUtil {

    /** 超管跨租户操作时携带的操作人所属租户，对齐设备基座详细设计 3.4/3.5 X-Operator-Tenant-Id 约定 */
    private static final String HEADER_OPERATOR_TENANT_ID = "X-Operator-Tenant-Id";

    private RequestUtil() {
    }

    /** 从 JWT Token 中提取当前登录用户名，Token 缺失或无效时返回 null（不抛异常）。 */
    public static String safeUsername(HttpServletRequest request) {
        try {
            return JwtUtil.getUserNameByToken(request);
        } catch (JeecgBootException ignored) {
            return null;
        }
    }

    /** 超管跨租户操作时的操作人所属租户，未携带时返回 null（表示非跨租户操作）。 */
    public static String operatorTenantId(HttpServletRequest request) {
        return request.getHeader(HEADER_OPERATOR_TENANT_ID);
    }
}
