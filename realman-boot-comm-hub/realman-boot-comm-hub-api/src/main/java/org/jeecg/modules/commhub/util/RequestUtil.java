package org.jeecg.modules.commhub.util;

import jakarta.servlet.http.HttpServletRequest;
import org.jeecg.common.exception.JeecgBootException;
import org.jeecg.common.system.util.JwtUtil;

/**
 * Controller 层 HTTP 请求工具方法，镜像 {@code realman-boot-iot}/{@code realman-boot-device-mgmt} 的同名工具类。
 */
public final class RequestUtil {

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
}
