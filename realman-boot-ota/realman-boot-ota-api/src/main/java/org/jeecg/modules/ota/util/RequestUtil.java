package org.jeecg.modules.ota.util;

import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;

/**
 * Controller 层 HTTP 请求工具方法，镜像其余 V2 新服务的同名工具类。
 */
public final class RequestUtil {

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
