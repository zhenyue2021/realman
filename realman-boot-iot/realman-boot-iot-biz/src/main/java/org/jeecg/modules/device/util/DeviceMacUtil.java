package org.jeecg.modules.device.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 物联网设备 MAC 地址识别工具类
 * 用于设备登录时获取本机 MAC 地址，作为设备唯一标识上报到平台
 */
public class DeviceMacUtil {

    /**
     * 【推荐】获取设备的主 MAC 地址（最可靠的方式）
     * 
     * 筛选策略：
     * 1. 排除回环接口（lo）
     * 2. 排除虚拟接口（docker0, veth*, br-* 等）
     * 3. 排除未启用的接口
     * 4. 优先选择有 IPv4 地址且非内网回环的物理网卡
     */
    public static String getPrimaryMacAddress() {
        try {
            // 优先策略：找到绑定了真实 IPv4 的物理网卡
            NetworkInterface bestInterface = findBestPhysicalInterface();
            if (bestInterface != null) {
                String mac = formatMac(bestInterface.getHardwareAddress());
                if (mac != null) return mac;
            }

            // 兜底策略：遍历所有网卡，取第一个有效的
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (isValidPhysicalInterface(ni)) {
                    String mac = formatMac(ni.getHardwareAddress());
                    if (mac != null) return mac;
                }
            }
        } catch (SocketException e) {
            throw new RuntimeException("获取 MAC 地址失败", e);
        }
        throw new RuntimeException("未找到有效的物理网卡 MAC 地址");
    }

    /**
     * 获取所有物理网卡的 MAC 地址信息（调试/管理用）
     */
    public static List<NetworkCardInfo> getAllPhysicalMacs() {
        List<NetworkCardInfo> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || ni.getHardwareAddress() == null) continue;

                String mac = formatMac(ni.getHardwareAddress());
                if (mac == null) continue;

                // 收集该网卡绑定的 IP
                List<String> ips = Collections.list(ni.getInetAddresses()).stream()
                        .map(InetAddress::getHostAddress)
                        .collect(Collectors.toList());

                result.add(new NetworkCardInfo(
                        ni.getName(),
                        ni.getDisplayName(),
                        mac,
                        ips,
                        ni.isUp(),
                        ni.isVirtual()
                ));
            }
        } catch (SocketException e) {
            throw new RuntimeException("枚举网卡失败", e);
        }
        return result;
    }

    // ===================== 内部方法 =====================

    /**
     * 找到最佳的物理网卡
     * 优先级：有非回环 IPv4 的物理网卡 > eth* > en* > wlan*
     */
    private static NetworkInterface findBestPhysicalInterface() throws SocketException {
        List<NetworkInterface> candidates = new ArrayList<>();

        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while (interfaces.hasMoreElements()) {
            NetworkInterface ni = interfaces.nextElement();
            if (isValidPhysicalInterface(ni)) {
                candidates.add(ni);
            }
        }

        if (candidates.isEmpty()) return null;

        // 按优先级排序：有真实 IPv4 的排前面，然后按网卡名称排序
        candidates.sort((a, b) -> {
            int scoreA = interfaceScore(a);
            int scoreB = interfaceScore(b);
            return Integer.compare(scoreB, scoreA); // 分数高的排前面
        });

        return candidates.get(0);
    }

    /**
     * 判断是否为有效的物理网卡
     */
    private static boolean isValidPhysicalInterface(NetworkInterface ni) throws SocketException {
        if (ni.isLoopback() || !ni.isUp() || ni.getHardwareAddress() == null) {
            return false;
        }

        String name = ni.getName().toLowerCase();

        // 排除常见的虚拟网卡
        String[] virtualPrefixes = {
            "docker", "veth", "br-", "virbr", "vmnet", "vbox",
            "tun", "tap", "flannel", "cni", "calico"
        };
        for (String prefix : virtualPrefixes) {
            if (name.startsWith(prefix)) return false;
        }

        return true;
    }

    /**
     * 网卡评分（分越高越优先）
     */
    private static int interfaceScore(NetworkInterface ni) {
        int score = 0;
        String name = ni.getName().toLowerCase();

        // 有非回环 IPv4 地址加分
        boolean hasRealIPv4 = Collections.list(ni.getInetAddresses()).stream()
                .anyMatch(addr -> addr instanceof Inet4Address
                        && !addr.isLoopbackAddress()
                        && !addr.isLinkLocalAddress());
        if (hasRealIPv4) score += 100;

        // 物理有线网卡加分（eth*, en*, ens*, eno*）
        if (name.matches("^(eth|en|ens|eno)\\d*$")) score += 50;

        // 无线网卡次之
        if (name.matches("^(wlan|wlp)\\d*$")) score += 30;

        // 虚拟网卡减分
        try {
            if (ni.isVirtual()) score -= 50;
        } catch (Exception ignored) {}

        return score;
    }

    /**
     * 将 byte[] 格式化为 MAC 字符串
     */
    private static String formatMac(byte[] mac) {
        if (mac == null || mac.length == 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < mac.length; i++) {
            sb.append(String.format("%02X", mac[i]));
            if (i < mac.length - 1) sb.append(":");
        }
        return sb.toString();
    }

    // ===================== 网卡信息 DTO =====================

    public static class NetworkCardInfo {
        private final String name;
        private final String displayName;
        private final String macAddress;
        private final List<String> ipAddresses;
        private final boolean up;
        private final boolean virtual_;

        public NetworkCardInfo(String name, String displayName, String macAddress,
                               List<String> ipAddresses, boolean up, boolean virtual_) {
            this.name = name;
            this.displayName = displayName;
            this.macAddress = macAddress;
            this.ipAddresses = ipAddresses;
            this.up = up;
            this.virtual_ = virtual_;
        }

        // Getters
        public String getName() { return name; }
        public String getDisplayName() { return displayName; }
        public String getMacAddress() { return macAddress; }
        public List<String> getIpAddresses() { return ipAddresses; }
        public boolean isUp() { return up; }
        public boolean isVirtual() { return virtual_; }

        @Override
        public String toString() {
            return String.format("[%s] %s | MAC: %s | IPs: %s | UP: %s | Virtual: %s",
                    name, displayName, macAddress, ipAddresses, up, virtual_);
        }
    }

    // ===================== 测试入口 =====================

/*    public static void main(String[] args) {
        System.out.println("===== 设备主 MAC 地址 =====");
        System.out.println(getPrimaryMacAddress());

        System.out.println("\n===== 所有物理网卡信息 =====");
        getAllPhysicalMacs().forEach(System.out::println);
    }*/
}
