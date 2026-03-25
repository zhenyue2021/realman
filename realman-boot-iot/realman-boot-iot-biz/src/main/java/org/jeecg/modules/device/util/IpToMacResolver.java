package org.jeecg.modules.device.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.nio.charset.Charset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 根据 IP 获取 MAC 地址的工具类
 * 
 * ┌────────────────────────────────────────────────────────────────┐
 * │  能力边界（这很重要）：                                          │
 * │                                                                │
 * │  ✅ 本机 IP    → 直接通过 NetworkInterface 获取                 │
 * │  ✅ 同子网 IP  → 先 ping 触发 ARP，再读 ARP 表                  │
 * │  ❌ 跨子网 IP  → 拿不到！MAC 在路由器那层就被替换了               │
 * │                                                                │
 * │  如果目标 IP 跨了子网/路由器，ARP 表里只会显示网关的 MAC，         │
 * │  而不是目标设备的 MAC。这是网络协议的根本限制，不是代码能解决的。    │
 * └────────────────────────────────────────────────────────────────┘
 */
public class IpToMacResolver {

    /** MAC 地址正则（兼容 Linux/Windows/Mac 格式） */
    private static final Pattern MAC_PATTERN = Pattern.compile(
            "([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}");

    /**
     * 根据 IP 地址获取 MAC 地址（核心方法）
     * 
     * @param ip 目标 IP 地址，例如 "192.168.1.100"
     * @return MAC 地址（大写冒号分隔），找不到返回 null
     */
    public static String getMacByIp(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            throw new IllegalArgumentException("IP 地址不能为空");
        }

        ip = ip.trim();

        // 1. 先判断是不是本机 IP，是的话直接走 NetworkInterface
        String localMac = getLocalMacByIp(ip);
        if (localMac != null) {
            return localMac;
        }

        // 2. 不是本机 → 走 ARP 查询（仅同子网有效）
        return getRemoteMacByArp(ip);
    }

    /**
     * 批量查询（适合扫描局域网设备）
     * 
     * @param ips IP 列表
     * @return IP → MAC 的映射（查不到的不包含在结果中）
     */
    public static Map<String, String> batchGetMac(List<String> ips) {
        Map<String, String> result = new LinkedHashMap<>();

        // 先批量 ping，填充 ARP 表
        batchPing(ips);

        // 一次性读取 ARP 表
        Map<String, String> arpTable = readFullArpTable();

        for (String ip : ips) {
            // 先查本机
            String mac = getLocalMacByIp(ip);
            if (mac != null) {
                result.put(ip, mac);
                continue;
            }
            // 再查 ARP 表
            mac = arpTable.get(ip);
            if (mac != null) {
                result.put(ip, normalizeMac(mac));
            }
        }

        return result;
    }

    /**
     * 扫描指定子网内所有在线设备的 MAC 地址
     * 
     * 例如：scanSubnet("192.168.1") 扫描 192.168.1.1 ~ 192.168.1.254
     * 
     * ⚠️ 注意：全子网扫描较慢（约 10-30 秒），建议异步调用
     * 
     * @param subnetPrefix 子网前缀，例如 "192.168.1"
     * @return IP → MAC 映射
     */
    public static Map<String, String> scanSubnet(String subnetPrefix) {
        System.out.println("[扫描] 开始扫描子网: " + subnetPrefix + ".0/24");

        List<String> ips = new ArrayList<>();
        for (int i = 1; i <= 254; i++) {
            ips.add(subnetPrefix + "." + i);
        }

        // 并发 ping（用 fping 或多线程 ping）
        batchPing(ips);

        // 等待 ARP 表更新
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}

        // 读取 ARP 表
        Map<String, String> arpTable = readFullArpTable();
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : arpTable.entrySet()) {
            if (entry.getKey().startsWith(subnetPrefix + ".")) {
                result.put(entry.getKey(), normalizeMac(entry.getValue()));
            }
        }

        System.out.println("[扫描] 发现 " + result.size() + " 台在线设备");
        return result;
    }


    // =============================================================
    //  本机 MAC 获取
    // =============================================================

    /**
     * 获取本机所有真实网卡的 MAC 地址（排除回环、虚拟网卡）
     *
     * @return MAC 地址列表，格式如 "AA:BB:CC:DD:EE:FF"
     */
    public static List<String> getLocalMacs() {
        List<String> macs = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) return macs;
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                // 跳过回环、未启用、虚拟接口
                if (ni.isLoopback() || !ni.isUp() || ni.isVirtual()) continue;
                byte[] mac = ni.getHardwareAddress();
                if (mac != null && mac.length > 0) {
                    macs.add(formatMac(mac));
                }
            }
        } catch (SocketException e) {
            System.out.println("[本机MAC] 枚举网卡失败: " + e.getMessage());
        }
        return macs;
    }

    /**
     * 如果 IP 是本机网卡的地址，直接通过 Java API 获取 MAC
     */
    private static String getLocalMacByIp(String ip) {
        try {
            InetAddress addr = InetAddress.getByName(ip);
            NetworkInterface ni = NetworkInterface.getByInetAddress(addr);
            if (ni == null) return null;

            byte[] mac = ni.getHardwareAddress();
            if (mac == null) return null;

            return formatMac(mac);
        } catch (Exception e) {
            return null;
        }
    }


    // =============================================================
    //  ARP 查询（同子网远端设备）
    // =============================================================

    /**
     * 通过 ARP 表获取同子网设备的 MAC
     * 
     * 原理：
     *   1. 先 ping 目标 IP（触发操作系统发送 ARP 请求）
     *   2. 等待 ARP 响应填充到系统 ARP 缓存
     *   3. 读取 ARP 表找到对应的 MAC
     */
    private static String getRemoteMacByArp(String ip) {
        try {
            // Step 1: ping 目标，触发 ARP
            //   为什么要先 ping？因为 ARP 缓存有过期时间（通常 1-20 分钟），
            //   如果之前没通信过，ARP 表里可能没有这个 IP 的记录
            boolean reachable = pingHost(ip);
            if (!reachable) {
                System.out.println("[ARP] 目标不可达: " + ip + "（可能离线或跨子网）");
                // 不要立即返回 null，设备可能禁 ping 但 ARP 仍然有记录
            }

            // Step 2: 等一下让 ARP 表更新
            Thread.sleep(500);

            // Step 3: 读取 ARP 表
            String mac = lookupArpTable(ip);
            if (mac != null) {
                return normalizeMac(mac);
            }

            System.out.println("[ARP] ARP 表中未找到: " + ip);
            return null;

        } catch (Exception e) {
            System.out.println("[ARP] 查询异常: " + e.getMessage());
            return null;
        }
    }

    /**
     * Ping 目标主机（触发 ARP）
     */
    private static boolean pingHost(String ip) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;

            if (os.contains("win")) {
                // Windows: ping -n 1 -w 1000
                cmd = new String[]{"ping", "-n", "1", "-w", "1000", ip};
            } else {
                // Linux/Mac: ping -c 1 -W 1
                cmd = new String[]{"ping", "-c", "1", "-W", "1", ip};
            }

            Process process = Runtime.getRuntime().exec(cmd);
            int exitCode = process.waitFor();
            return exitCode == 0;

        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 并发 ping 多个 IP（加速 ARP 表填充）
     */
    private static void batchPing(List<String> ips) {
        String os = System.getProperty("os.name").toLowerCase();

        if (!os.contains("win")) {
            // Linux/Mac: 尝试用 fping（更快）
            try {
                String ipList = String.join(" ", ips);
                String[] cmd = {"/bin/sh", "-c", "fping -c 1 -t 500 " + ipList + " 2>/dev/null"};
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
                return;
            } catch (Exception ignored) {
                // fping 不可用，fallback 到多线程 ping
            }
        }

        // Fallback: 多线程 ping
        List<Thread> threads = new ArrayList<>();
        for (String ip : ips) {
            Thread t = new Thread(() -> pingHost(ip));
            t.setDaemon(true);
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            try { t.join(2000); } catch (InterruptedException ignored) {}
        }
    }

    /**
     * 从系统 ARP 表查询指定 IP 的 MAC
     * 
     * ARP 命令输出示例：
     * 
     * Linux:
     *   192.168.1.1  ether  aa:bb:cc:dd:ee:ff  C  eth0
     * 
     * Windows:
     *   192.168.1.1       aa-bb-cc-dd-ee-ff     动态
     * 
     * Mac:
     *   192.168.1.1 (192.168.1.1) at aa:bb:cc:dd:ee:ff on en0
     */
    private static String lookupArpTable(String ip) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;

            if (os.contains("win")) {
                // Windows 不用 "arp -a <ip>"，因为跨接口时可能无输出；改为读全表再过滤
                cmd = new String[]{"arp", "-a"};
            } else {
                // Linux / Mac
                cmd = new String[]{"arp", "-n", ip};
            }

            Process process = Runtime.getRuntime().exec(cmd);
            // Windows 中文系统 arp 输出为 GBK，需显式指定，避免乱码导致正则匹配失败
            Charset charset = os.contains("win") ? Charset.forName("GBK") : Charset.defaultCharset();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset));

            String line;
            while ((line = reader.readLine()) != null) {
                // 确保这一行包含目标 IP
                if (!line.contains(ip)) continue;

                // 用正则提取 MAC 地址
                Matcher matcher = MAC_PATTERN.matcher(line);
                if (matcher.find()) {
                    String mac = matcher.group();
                    // 过滤掉 ff:ff:ff:ff:ff:ff（广播地址）和 00:00:00:00:00:00
                    if (!mac.replaceAll("[:-]", "").matches("^(0{12}|[fF]{12})$")) {
                        return mac;
                    }
                }
            }

            process.waitFor();
            return null;

        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 读取完整的 ARP 表（批量查询用）
     * 
     * @return IP → MAC 映射
     */
    private static Map<String, String> readFullArpTable() {
        Map<String, String> table = new HashMap<>();
        try {
            String os = System.getProperty("os.name").toLowerCase();
            String[] cmd;

            if (os.contains("win")) {
                cmd = new String[]{"arp", "-a"};
            } else {
                cmd = new String[]{"arp", "-an"};
            }

            Process process = Runtime.getRuntime().exec(cmd);
            Charset charset = os.contains("win") ? Charset.forName("GBK") : Charset.defaultCharset();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset));

            // IP 地址正则
            Pattern ipPattern = Pattern.compile(
                    "(\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3})");

            String line;
            while ((line = reader.readLine()) != null) {
                Matcher ipMatcher = ipPattern.matcher(line);
                Matcher macMatcher = MAC_PATTERN.matcher(line);

                if (ipMatcher.find() && macMatcher.find()) {
                    String ip = ipMatcher.group(1);
                    String mac = macMatcher.group();
                    if (!mac.replaceAll("[:-]", "").matches("^(0{12}|[fF]{12})$")) {
                        table.put(ip, mac);
                    }
                }
            }

            process.waitFor();
        } catch (Exception e) {
            System.out.println("[ARP] 读取 ARP 表失败: " + e.getMessage());
        }

        return table;
    }


    // =============================================================
    //  格式化工具
    // =============================================================

    private static String formatMac(byte[] mac) {
        if (mac == null || mac.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X", mac[i]));
            if (i < mac.length - 1) sb.append(":");
        }
        return sb.toString();
    }

    /** 统一 MAC 格式为大写冒号分隔 */
    private static String normalizeMac(String mac) {
        return mac.toUpperCase()
                   .replaceAll("[:-]", "")
                   .replaceAll("(.{2})", "$1:")
                   .replaceAll(":$", "");
    }


    // =============================================================
    //  测试入口
    // =============================================================

    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("  IP → MAC 地址查询工具");
        System.out.println("==========================================\n");

        // 测试 1: 枚举本机所有真实网卡 MAC
        System.out.println("【1】本机所有网卡 MAC");
        List<String> localMacs = getLocalMacs();
        if (localMacs.isEmpty()) {
            System.out.println("结果: 未找到任何物理网卡");
        } else {
            localMacs.forEach(m -> System.out.println("  " + m));
        }
        System.out.println();

        // 测试 2: 查询网关（通常是路由器，同子网）
        System.out.println("【2】查询网关 172.16.13.165");
        String mac2 = getMacByIp("172.16.13.165");
        System.out.println("结果: " + (mac2 != null ? mac2 : "未找到（可能不在同一子网）"));
        System.out.println();

        // 测试 3: 查询局域网内其他设备
        System.out.println("【3】查询局域网设备 192.168.1.100");
        String mac3 = getMacByIp("192.168.1.100");
        System.out.println("结果: " + (mac3 != null ? mac3 : "未找到（可能离线或跨子网）"));
        System.out.println();

        // 测试 4: 查询外网 IP（一定拿不到真实 MAC）
        System.out.println("【4】查询外网 IP 8.8.8.8");
        String mac4 = getMacByIp("8.8.8.8");
        System.out.println("结果: " + (mac4 != null ? mac4 + " ← 这是网关的MAC，不是谷歌的！" : "未找到"));
        System.out.println();

        // 测试 5: 查询局域网 172.16.44.99
        System.out.println("【5】查询局域网 IP 172.16.44.66");
        String mac5 = getMacByIp("172.16.44.66");
        System.out.println("结果: " + (mac5 != null ? mac5 + " ← 172.16.44.66 → " : "未找到"));
        System.out.println();

        // 测试 6: 批量查询
        System.out.println("【6】批量查询");
        Map<String, String> batch = batchGetMac(Arrays.asList(
                "192.168.1.1", "192.168.1.100", "192.168.1.200"
        ));
        batch.forEach((ip, mac) -> System.out.println("  " + ip + " → " + mac));
        System.out.println();

        // 测试 6: 子网扫描（耗时较长）
        // System.out.println("【6】扫描子网 192.168.1.0/24");
        // Map<String, String> subnet = scanSubnet("192.168.1");
        // subnet.forEach((ip, mac) -> System.out.println("  " + ip + " → " + mac));
    }
}
