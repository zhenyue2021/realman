package org.jeecg.modules.device.emqx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import jakarta.annotation.PostConstruct;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * EMQX Management API 客户端：查询 Broker 上当前已连接的 MQTT 客户端。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "mqtt", name = "enabled", havingValue = "true", matchIfMissing = false)
public class EmqxManagementClient {

    private static final int PAGE_SIZE = 1000;

    /** EMQX 全局认证链中 Built-in Database 的 authenticator id（URL 编码后） */
    private static final String BUILT_IN_AUTH_ID = "password_based%3Abuilt_in_database";

    private RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${mqtt.emqx.api-url:}")
    private String apiUrl;

    @Value("${mqtt.broker.url:tcp://localhost:1883}")
    private String brokerUrl;

    @Value("${mqtt.emqx.api-username:}")
    private String apiUsername;

    @Value("${mqtt.emqx.api-password:}")
    private String apiPassword;

    @Value("${mqtt.emqx.api-timeout-ms:5000}")
    private int apiTimeoutMs;

    @Value("${mqtt.broker.username:iot-platform}")
    private String platformUsername;

    @Value("${mqtt.emqx.ensure-platform-superuser:true}")
    private boolean ensurePlatformSuperuserEnabled;

    public EmqxManagementClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void initRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(apiTimeoutMs);
        factory.setReadTimeout(apiTimeoutMs);
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 列出 EMQX 上 conn_state=connected 的客户端 deviceCode（优先 username）。
     */
    public List<String> listConnectedDeviceCodes() {
        String baseUrl = resolveApiUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("[EmqxApi] 未配置 mqtt.emqx.api-url，且无法从 broker.url 推导，跳过对账");
            return Collections.emptyList();
        }

        List<String> deviceCodes = new ArrayList<>();
        int page = 1;
        while (true) {
            URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                    .path("/api/v5/clients")
                    .queryParam("conn_state", "connected")
                    .queryParam("page", page)
                    .queryParam("limit", PAGE_SIZE)
                    .build(true)
                    .toUri();
            JsonNode root;
            try {
                String body = restTemplate.exchange(uri, HttpMethod.GET, authEntity(), String.class).getBody();
                if (body == null || body.isBlank()) {
                    break;
                }
                root = objectMapper.readTree(body);
            } catch (RestClientException e) {
                log.warn("[EmqxApi] 查询 connected clients 失败 url={}", uri, e);
                break;
            } catch (Exception e) {
                log.warn("[EmqxApi] 解析 connected clients 响应失败 url={}", uri, e);
                break;
            }

            JsonNode data = root.path("data");
            if (!data.isArray() || data.isEmpty()) {
                break;
            }
            for (JsonNode client : data) {
                String deviceCode = resolveDeviceCode(client);
                if (deviceCode != null) {
                    deviceCodes.add(deviceCode);
                }
            }
            if (data.size() < PAGE_SIZE) {
                break;
            }
            page++;
        }
        return deviceCodes;
    }

    /**
     * 将 EMQX Built-in Database 中的平台账号设为 superuser，以允许订阅 {@code $SYS/#}。
     *
     * <p>部署文档要求在 Built-in 添加 {@code iot-platform} 用户；该用户若先于 HTTP Auth 匹配，
     * CONNECT 不会回调 {@code /internal/mqtt/auth}，{@code is_superuser} 无法通过 HTTP 下发。
     */
    public boolean ensurePlatformSuperuser() {
        if (!ensurePlatformSuperuserEnabled) {
            log.info("[EmqxApi] mqtt.emqx.ensure-platform-superuser=false，跳过 superuser 修复");
            return false;
        }
        String baseUrl = resolveApiUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            log.warn("[EmqxApi] 未配置 mqtt.emqx.api-url，无法自动设置平台 superuser");
            return false;
        }
        URI uri = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/api/v5/authentication/")
                .path(BUILT_IN_AUTH_ID)
                .path("/users/")
                .path(platformUsername)
                .build(true)
                .toUri();
        Map<String, Object> body = new HashMap<>(1);
        body.put("is_superuser", true);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (apiUsername != null && !apiUsername.isBlank()) {
            headers.setBasicAuth(apiUsername, apiPassword != null ? apiPassword : "");
        }
        try {
            restTemplate.exchange(uri, HttpMethod.PUT, new HttpEntity<>(body, headers), String.class);
            log.info("[EmqxApi] 已将 Built-in 用户 {} 设为 superuser（$SYS 订阅）", platformUsername);
            return true;
        } catch (HttpClientErrorException.NotFound e) {
            log.info("[EmqxApi] Built-in 无用户 {}，平台 CONNECT 应走 HTTP Auth", platformUsername);
            return false;
        } catch (RestClientException e) {
            log.warn("[EmqxApi] 设置 superuser 失败 user={} url={}", platformUsername, uri, e);
            return false;
        }
    }

    private String resolveDeviceCode(JsonNode client) {
        String username = textOrNull(client.get("username"));
        if (username != null && !username.isBlank() && !"unknown".equalsIgnoreCase(username)) {
            return username;
        }
        return textOrNull(client.get("clientid"));
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String text = node.asText();
        return text == null || text.isBlank() ? null : text;
    }

    private HttpEntity<Void> authEntity() {
        HttpHeaders headers = new HttpHeaders();
        if (apiUsername != null && !apiUsername.isBlank()) {
            headers.setBasicAuth(apiUsername, apiPassword != null ? apiPassword : "");
        }
        return new HttpEntity<>(headers);
    }

    String resolveApiUrl() {
        if (apiUrl != null && !apiUrl.isBlank()) {
            return trimTrailingSlash(apiUrl);
        }
        return deriveApiUrlFromBroker(brokerUrl);
    }

    static String deriveApiUrlFromBroker(String brokerUrl) {
        if (brokerUrl == null || brokerUrl.isBlank()) {
            return null;
        }
        String normalized = brokerUrl.trim();
        String hostPort;
        if (normalized.startsWith("tcp://")) {
            hostPort = normalized.substring("tcp://".length());
        } else if (normalized.startsWith("ssl://")) {
            hostPort = normalized.substring("ssl://".length());
        } else if (normalized.startsWith("mqtt://")) {
            hostPort = normalized.substring("mqtt://".length());
        } else if (normalized.startsWith("mqtts://")) {
            hostPort = normalized.substring("mqtts://".length());
        } else {
            return null;
        }
        int slash = hostPort.indexOf('/');
        if (slash >= 0) {
            hostPort = hostPort.substring(0, slash);
        }
        String host;
        if (hostPort.startsWith("[")) {
            int end = hostPort.indexOf(']');
            if (end < 0) {
                return null;
            }
            host = hostPort.substring(0, end + 1);
        } else {
            int colon = hostPort.lastIndexOf(':');
            host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        }
        if (host.isBlank()) {
            return null;
        }
        return "http://" + host + ":18083";
    }

    private static String trimTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
