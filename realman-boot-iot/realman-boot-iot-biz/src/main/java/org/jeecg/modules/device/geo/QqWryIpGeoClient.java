package org.jeecg.modules.device.geo;

import com.github.jarod.qqwry.IPZone;
import com.github.jarod.qqwry.QQWry;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 使用纯真 {@code qqwry.dat}（IPv4）离线解析行政区划展示串，格式与 {@link AmapIpGeoClient} 一致。
 *
 * <p>配置 {@code device.mqtt-auth.ip-geo.qqwry.path} 为服务器上 dat 绝对路径；未配置或文件不存在时不参与解析。
 * <p>定时用运维脚本替换同路径文件时，可开启 {@code qqwry.hot-reload}，按 {@code qqwry.reload-check-ms} 检测修改时间并内存热加载（失败则沿用旧库）。
 */
@Slf4j
@Component
public class QqWryIpGeoClient {

    private static final String[] MUNICIPALITIES = {"北京市", "上海市", "天津市", "重庆市"};

    /** mainInfo 常见分段符：en dash、em dash、半角连字符、全角连字符 */
    private static final Pattern MAIN_INFO_SEGMENT_DELIM = Pattern.compile("[\\u2013\\u2014\\-－]+");

    private static final Pattern PROVINCE_PREFIX =
            Pattern.compile("^([\\u4e00-\\u9fa5]+?(?:省|自治区|特别行政区))");
    private static final Pattern CITY_AT_START = Pattern.compile("^([\\u4e00-\\u9fa5]+?市)");
    private static final Pattern DISTRICT_TOKEN = Pattern.compile("([\\u4e00-\\u9fa5]+?(?:区|县|旗))");
    private static final Pattern ISP_STRIP =
            Pattern.compile("(电信|联通|移动|铁通|教育网|广电网|網通|网通|长城|鹏博士|有线|信息|科技|公司|网吧|中心|分局|节点).*$");

    @Value("${device.mqtt-auth.ip-geo.qqwry.enabled:true}")
    private boolean enabled;

    @Value("${device.mqtt-auth.ip-geo.qqwry.path:}")
    private String qqwryPath;

    private volatile QQWry qqwry;

    /** 上次成功载入的 dat 修改时间，用于热更新判断 */
    private volatile FileTime lastLoadedMtime;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("[QqWry] device.mqtt-auth.ip-geo.qqwry.enabled=false，跳过加载");
            return;
        }
        if (qqwryPath == null || qqwryPath.isBlank()) {
            log.warn("[QqWry] 未配置 device.mqtt-auth.ip-geo.qqwry.path，离线库不可用");
            return;
        }
        Path p = Paths.get(qqwryPath.trim());
        if (!Files.isRegularFile(p)) {
            log.warn("[QqWry] 文件不存在: {}", p.toAbsolutePath());
            return;
        }
        try {
            qqwry = new QQWry(p);
            lastLoadedMtime = Files.getLastModifiedTime(p);
            log.info("[QqWry] 已加载 {}", p.toAbsolutePath());
        } catch (IOException e) {
            log.error("[QqWry] 加载失败 {}", p.toAbsolutePath(), e);
        }
    }

    /**
     * 若 path 指向文件修改时间晚于当前内存库，则重新构造 {@link QQWry}；用于与外部定时替换 dat 配合，无需重启进程。
     */
    public synchronized void tryReloadFromDiskIfModified() {
        if (!enabled) {
            return;
        }
        if (qqwryPath == null || qqwryPath.isBlank()) {
            return;
        }
        Path p = Paths.get(qqwryPath.trim());
        if (!Files.isRegularFile(p)) {
            return;
        }
        try {
            FileTime mtime = Files.getLastModifiedTime(p);
            if (qqwry != null && lastLoadedMtime != null && mtime.compareTo(lastLoadedMtime) <= 0) {
                return;
            }
            QQWry next = new QQWry(p);
            this.qqwry = next;
            this.lastLoadedMtime = mtime;
            log.info("[QqWry] 已热加载 {}", p.toAbsolutePath());
        } catch (IOException e) {
            log.error("[QqWry] 热加载失败，沿用旧库 {}", p.toAbsolutePath(), e);
        }
    }

    /**
     * @param ipv4OrIpv6 非 IPv4、库未加载时返回 null；内网同高德文案。
     */
    public String resolveAdministrativeAddress(String ipv4OrIpv6) {
        if (qqwry == null) {
            return null;
        }
        if (ipv4OrIpv6 == null || ipv4OrIpv6.isBlank()) {
            return null;
        }
        String ip = ipv4OrIpv6.trim();
        if (AmapIpGeoClient.isPrivateOrLocal(ip)) {
            return "内网IP-" + ip;
        }
        if (!ip.contains(".")) {
            return null;
        }
        try {
            IPZone z = qqwry.findIP(ip);
            return formatZone(z);
        } catch (IllegalArgumentException e) {
            return null;
        } catch (Exception e) {
            log.warn("[QqWry] findIP 异常 ip={}", ip, e);
            return null;
        }
    }

    private String formatZone(IPZone z) {
        String main = safe(z.getMainInfo());
        String subRaw = safe(z.getSubInfo());
        String sub = stripIspSuffix(subRaw);
        String merged = (main + sub).trim();
        if (merged.isEmpty() || looksUnknown(main, subRaw, merged)) {
            return null;
        }

        Optional<String[]> dash = parseChinaDashMainInfo(main);
        if (dash.isPresent()) {
            String[] t = dash.get();
            String province = t[0];
            String city = t[1];
            String district = t[2];
            if (district == null || district.isBlank()) {
                district = firstDistrict(sub);
            }
            return AdministrativeAddressFormatter.formatProvinceCityDistrict(province, city, district);
        }

        for (String m : MUNICIPALITIES) {
            if (merged.startsWith(m)) {
                String district = firstDistrict(merged.substring(m.length()));
                return AdministrativeAddressFormatter.formatProvinceCityDistrict(m, m, district);
            }
        }
        if (isMunicipalityName(main)) {
            return AdministrativeAddressFormatter.formatProvinceCityDistrict(main, main, firstDistrict(sub));
        }

        Matcher pm = PROVINCE_PREFIX.matcher(merged);
        if (pm.find()) {
            String province = pm.group(1);
            String tail = merged.substring(pm.end());
            Matcher cm = CITY_AT_START.matcher(tail);
            String city = null;
            if (cm.find()) {
                city = cm.group(1);
                tail = tail.substring(cm.end());
            }
            String district = firstDistrict(tail);
            return AdministrativeAddressFormatter.formatProvinceCityDistrict(province, city, district);
        }

        if (main.endsWith("市") && !isMunicipalityName(main)) {
            return AdministrativeAddressFormatter.formatProvinceCityDistrict(null, main, firstDistrict(sub));
        }

        return null;
    }

    /**
     * 解析 {@code mainInfo} 如「中国–北京–北京」「中国–广东–深圳–南山」：国家–省/直辖市–市–区（区可缺省）。
     *
     * @return 长度为 3 的数组 [province, city, district]，district 可为 null
     */
    private static Optional<String[]> parseChinaDashMainInfo(String main) {
        if (main == null || main.isBlank()) {
            return Optional.empty();
        }
        if (!MAIN_INFO_SEGMENT_DELIM.matcher(main).find()) {
            return Optional.empty();
        }
        String[] raw = MAIN_INFO_SEGMENT_DELIM.split(main);
        List<String> parts = new ArrayList<>();
        for (String s : raw) {
            String t = s.trim();
            if (!t.isEmpty()) {
                parts.add(t);
            }
        }
        if (parts.isEmpty()) {
            return Optional.empty();
        }
        if ("中国".equals(parts.get(0))) {
            parts.remove(0);
        }
        if (parts.size() < 2) {
            return Optional.empty();
        }

        String province = normalizeProvinceToken(parts.get(0));
        String city = normalizeCityToken(parts.get(1));
        String district = null;
        if (parts.size() >= 3) {
            StringBuilder dist = new StringBuilder(parts.get(2));
            for (int i = 3; i < parts.size(); i++) {
                dist.append(parts.get(i));
            }
            district = normalizeDistrictToken(dist.toString());
        }
        return Optional.of(new String[] {province, city, district});
    }

    private static boolean isMunicipalityShort(String s) {
        return "北京".equals(s) || "上海".equals(s) || "天津".equals(s) || "重庆".equals(s);
    }

    private static String normalizeProvinceToken(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        s = s.trim();
        if (isMunicipalityShort(s)) {
            return s + "市";
        }
        if (s.endsWith("省") || s.endsWith("自治区") || s.endsWith("特别行政区")) {
            return s;
        }
        if (isMunicipalityName(s)) {
            return s;
        }
        return switch (s) {
            case "广西" -> "广西壮族自治区";
            case "内蒙古" -> "内蒙古自治区";
            case "西藏" -> "西藏自治区";
            case "宁夏" -> "宁夏回族自治区";
            case "新疆" -> "新疆维吾尔自治区";
            default -> {
                if (s.endsWith("市")) {
                    yield s;
                }
                yield s + "省";
            }
        };
    }

    private static String normalizeCityToken(String s) {
        if (s == null || s.isBlank()) {
            return s;
        }
        s = s.trim();
        if (isMunicipalityShort(s)) {
            return s + "市";
        }
        if (isMunicipalityName(s)) {
            return s;
        }
        if (s.endsWith("市")
                || s.endsWith("州")
                || s.endsWith("盟")
                || s.endsWith("县")
                || s.endsWith("地区")) {
            return s;
        }
        return s + "市";
    }

    /** 区县片段：无「区县旗市」后缀时补「区」（如 南山→南山区；已是 县 则保留） */
    private static String normalizeDistrictToken(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        s = s.trim();
        if (s.endsWith("区") || s.endsWith("县") || s.endsWith("旗") || s.endsWith("市")) {
            return s;
        }
        return s + "区";
    }

    private static boolean isMunicipalityName(String name) {
        if (name == null) {
            return false;
        }
        for (String m : MUNICIPALITIES) {
            if (m.equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksUnknown(String main, String subRaw, String merged) {
        String u = merged.toUpperCase();
        return merged.contains("局域网")
                || merged.contains("IANA")
                || merged.contains("保留地址")
                || merged.contains("保留")
                || merged.contains("纯真网络")
                || u.contains("CZ88");
    }

    private static String stripIspSuffix(String s) {
        if (s.isEmpty()) {
            return "";
        }
        return ISP_STRIP.matcher(s).replaceFirst("").trim();
    }

    private static String firstDistrict(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        Matcher m = DISTRICT_TOKEN.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
