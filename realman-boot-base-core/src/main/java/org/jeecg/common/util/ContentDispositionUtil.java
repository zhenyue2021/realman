package org.jeecg.common.util;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 生成 Content-Disposition 头（兼容中文文件名）
 *
 * <p>Tomcat 会拒绝包含 Unicode 的响应头（只能 0-255），因此不能直接设置
 * <code>filename="中文.xlsx"</code>。这里统一使用：
 * <ul>
 *   <li><code>filename="ASCII fallback"</code>（兼容老客户端）</li>
 *   <li><code>filename*=UTF-8''urlEncoded</code>（标准写法，支持中文）</li>
 * </ul>
 *
 * <p>如果前端把 <code>UTF-8''</code> 也展示出来，说明前端对 filename* 解析不规范，
 * 应按 RFC5987 在 <code>''</code> 后取真实文件名并做 URL decode。
 */
public final class ContentDispositionUtil {

    private ContentDispositionUtil() {
    }

    public static String attachment(String filename) {
        String safe = filename == null ? "download" : filename.trim();
        if (safe.isEmpty()) {
            safe = "download";
        }
        String fallback = toAsciiFallback(safe);
        String encoded = encodeUtf8Rfc5987(safe);
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String encodeUtf8Rfc5987(String s) {
        // URLEncoder uses application/x-www-form-urlencoded (space -> '+'), fix to '%20' for headers
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String toAsciiFallback(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\' || c == '\r' || c == '\n') {
                sb.append('_');
                continue;
            }
            if (c >= 0x20 && c <= 0x7E) {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "download" : out;
    }
}

