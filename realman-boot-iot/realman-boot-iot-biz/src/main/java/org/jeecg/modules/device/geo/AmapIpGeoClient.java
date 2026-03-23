package org.jeecg.modules.device.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;

/**
 * 使用高德开放平台将公网 IP 解析为中文行政区划文案（如 北京市北京市门头沟区）。
 *
 * <p>需配置 {@code device.mqtt-auth.ip-geo.amap-key}（与高德「Web 服务」类型 Key 一致）。
 * 未配置或调用失败时由调用方回退为仅存储 IP。
 */
@Slf4j
@Component
public class AmapIpGeoClient {

    private static final String IP_URL = "https://restapi.amap.com/v3/ip";
    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    @Value("${device.mqtt-auth.ip-geo.amap-key:eb59b91a276cb2ee7ddf7cc5d0d97c2b}")
    private String amapWebKey;

    @Value("${device.mqtt-auth.ip-geo.enabled:true}")
    private boolean enabled;

    public AmapIpGeoClient() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(3000);
        f.setReadTimeout(8000);
        this.restTemplate = new RestTemplate(f);
    }

    /**
     * @param ipv4OrIpv6 已规范化的 IP 字符串
     * @return 行政区划描述；无法解析时返回 null
     */
    public String resolveAdministrativeAddress(String ipv4OrIpv6) {
        if (!enabled || amapWebKey == null || amapWebKey.isBlank()) {
            return null;
        }
        if (ipv4OrIpv6 == null || ipv4OrIpv6.isBlank()) {
            return null;
        }
        String ip = ipv4OrIpv6.trim();
        if (isPrivateOrLocal(ip)) {
            return "内网IP-" + ip;
        }
        // 高德 v3/ip 目前以 IPv4 为主；IPv6 无结果时返回 null
        if (!ip.contains(".")) {
            return null;
        }
        try {
            String url = UriComponentsBuilder.fromUriString(IP_URL)
                    .queryParam("ip", ip)
                    .queryParam("key", amapWebKey)
                    .encode(StandardCharsets.UTF_8)
                    .toUriString();
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            if (!"1".equals(text(root, "status"))) {
                log.debug("[IpGeo] v3/ip 未命中 ip={} info={}", ip, text(root, "info"));
                return null;
            }
            String rectangle = text(root, "rectangle");
            if (rectangle != null && !rectangle.isBlank()) {
                String fromRegeo = regeoByRectangleCenter(rectangle);
                if (fromRegeo != null && !fromRegeo.isBlank()) {
                    return fromRegeo;
                }
            }
            return formatFromIpEndpoint(root);
        } catch (RestClientException e) {
            log.warn("[IpGeo] 请求高德失败 ip={}", ip, e);
            return null;
        } catch (Exception e) {
            log.warn("[IpGeo] 解析响应失败 ip={}", ip, e);
            return null;
        }
    }

    private String regeoByRectangleCenter(String rectangle) {
        // 格式：左下经度,左下纬度;右上经度,右上纬度
        String[] parts = rectangle.split(";");
        if (parts.length != 2) {
            return null;
        }
        String[] lb = parts[0].split(",");
        String[] rt = parts[1].split(",");
        if (lb.length != 2 || rt.length != 2) {
            return null;
        }
        try {
            double lng = (Double.parseDouble(lb[0]) + Double.parseDouble(rt[0])) / 2.0;
            double lat = (Double.parseDouble(lb[1]) + Double.parseDouble(rt[1])) / 2.0;
            String location = lng + "," + lat;
            String url = UriComponentsBuilder.fromUriString(REGEO_URL)
                    .queryParam("location", location)
                    .queryParam("key", amapWebKey)
                    .queryParam("radius", "1000")
                    .queryParam("extensions", "base")
                    .encode(StandardCharsets.UTF_8)
                    .toUriString();
            ResponseEntity<String> resp = restTemplate.getForEntity(url, String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
                return null;
            }
            JsonNode root = objectMapper.readTree(resp.getBody());
            if (!"1".equals(text(root, "status"))) {
                return null;
            }
            JsonNode comp = root.path("regeocode").path("addressComponent");
            if (comp.isMissingNode() || comp.isNull()) {
                return null;
            }
            String province = jsonText(comp, "province");
            String city = jsonTextCity(comp.get("city"));
            String district = jsonText(comp, "district");
            return formatProvinceCityDistrict(province, city, district);
        } catch (Exception e) {
            log.debug("[IpGeo] regeo 失败 rectangle={}", rectangle, e);
            return null;
        }
    }

    /** v3/ip 仅到省市时的兜底拼接 */
    private String formatFromIpEndpoint(JsonNode ipRoot) {
        String province = text(ipRoot, "province");
        String city = jsonTextCity(ipRoot.get("city"));
        return formatProvinceCityDistrict(province, city, null);
    }

    /**
     * 直辖市常见：city 为空数组，展示为「省 + 省 + 区」与业务示例「北京市北京市门头沟区」一致
     */
    static String formatProvinceCityDistrict(String province, String city, String district) {
        if (isEmpty(province)) {
            return null;
        }
        String prov = province.trim();
        String cit = isEmpty(city) ? prov : city.trim();
        String dist = district == null ? "" : district.trim();
        StringBuilder sb = new StringBuilder();
        sb.append(prov);
        if (!cit.isEmpty()) {
            sb.append(cit);
        }
        if (!dist.isEmpty()) {
            sb.append(dist);
        }
        return sb.toString();
    }

    private static String jsonText(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        return jsonTextCity(v);
    }

    private static String jsonTextCity(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) {
            return null;
        }
        if (v.isArray()) {
            if (v.isEmpty()) {
                return null;
            }
            return v.get(0).asText(null);
        }
        String s = v.asText(null);
        if (s == null || s.isBlank() || "[]".equals(s)) {
            return null;
        }
        return s;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode()) {
            return null;
        }
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        return v.asText(null);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }

    static boolean isPrivateOrLocal(String ip) {
        try {
            byte[] addr = InetAddress.getByName(ip).getAddress();
            if (addr.length == 4) {
                int b0 = addr[0] & 0xff;
                int b1 = addr[1] & 0xff;
                if (b0 == 10) {
                    return true;
                }
                if (b0 == 172 && b1 >= 16 && b1 <= 31) {
                    return true;
                }
                if (b0 == 192 && b1 == 168) {
                    return true;
                }
                if (b0 == 127) {
                    return true;
                }
                return false;
            }
            InetAddress ia = InetAddress.getByName(ip);
            return ia.isLoopbackAddress() || ia.isLinkLocalAddress() || ia.isSiteLocalAddress();
        } catch (Exception e) {
            return true;
        }
    }
}
