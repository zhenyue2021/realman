package org.jeecg.modules.device.util;

import jakarta.servlet.http.HttpServletRequest;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Locale;

/**
 * 客户端 MAC 地址解析工具（最佳努力，仅在内网/同网段场景下可能成功）
 *
 * <p>注意：在公网或跨网关场景下，服务端无法直接获取终端真实 MAC 地址，
 * 此工具仅在前端不传 mac 时，尝试通过 ARP 表在局域网中反查。
 */
public class MacResolveUtil {

    /**
     * 尝试获取客户端 MAC：
     * <ol>
     *   <li>若 macFromClient 非空，直接返回（当前场景前端不传，可为 null）</li>
     *   <li>否则通过 ARP 表在内网中按 IP 反查 MAC</li>
     *   <li>公网/跨网关通常拿不到，返回 null</li>
     * </ol>
     */
    public static String resolveClientMac(HttpServletRequest request, String macFromClient) {
        if (macFromClient != null && !macFromClient.isBlank()) {
            return macFromClient.trim();
        }

        String ip = extractClientIp(request);
        if (ip == null || ip.isBlank()
                || "127.0.0.1".equals(ip)
                || "0:0:0:0:0:0:0:1".equals(ip)) {
            return null;
        }

        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            Process p;
            if (os.contains("win")) {
                p = Runtime.getRuntime().exec("arp -a " + ip);
            } else {
                p = Runtime.getRuntime().exec(new String[]{"sh", "-c", "arp -n " + ip});
            }
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.contains(ip)) {
                        String mac = extractMacFromLine(line);
                        if (mac != null) {
                            return mac;
                        }
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** 从常见 arp 输出行中提取 MAC 地址 */
    private static String extractMacFromLine(String line) {
        if (line == null) {
            return null;
        }
        line = line.trim().replaceAll("\\s+", " ");
        String[] parts = line.split(" ");
        for (String part : parts) {
            String p = part.trim();
            if (p.matches("(?i)([0-9A-F]{2}[:-]){5}[0-9A-F]{2}")) {
                return p;
            }
        }
        return null;
    }

    /** 兼容代理场景的客户端 IP 提取 */
    private static String extractClientIp(HttpServletRequest request) {
        String[] headers = {
                "X-Forwarded-For",
                "X-Real-IP",
                "Proxy-Client-IP",
                "WL-Proxy-Client-IP",
                "HTTP_CLIENT_IP",
                "HTTP_X_FORWARDED_FOR"
        };
        for (String h : headers) {
            String ip = request.getHeader(h);
            if (ip != null && !ip.isBlank() && !"unknown".equalsIgnoreCase(ip)) {
                if (ip.contains(",")) {
                    ip = ip.split(",")[0].trim();
                }
                return ip;
            }
        }
        return request.getRemoteAddr();
    }
}

