package org.jeecg.modules.device.service.webrtc;

import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * turn_router 调度客户端（POST /api/v1/route/turn）。
 */
@Slf4j
@Service
public class TurnRouterClient {

    private static final String ROUTE_PATH = "/api/v1/route/turn";

    private final WebRtcProperties webRtcProperties;
    private final RestTemplate restTemplate;

    public TurnRouterClient(WebRtcProperties webRtcProperties) {
        this.webRtcProperties = webRtcProperties;
        this.restTemplate = buildRestTemplate();
    }

    public TurnRouteResult route(String callId,
                                 String robotProvince,
                                 String robotCity,
                                 String browserProvince,
                                 String browserCity) {
        WebRtcProperties.TurnRouter router = webRtcProperties.getTurnRouter();
        String baseUrl = router.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new RuntimeException("turn_router 未配置（webrtc.turn-router.base-url）");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("callId", callId);
        body.put("robotProvince", robotProvince);
        if (robotCity != null && !robotCity.isBlank()) {
            body.put("robotCity", robotCity);
        }
        body.put("browserProvince", browserProvince);
        if (browserCity != null && !browserCity.isBlank()) {
            body.put("browserCity", browserCity);
        }

        String url = trimTrailingSlash(baseUrl) + ROUTE_PATH;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new RuntimeException("turn_router 响应异常: HTTP " + response.getStatusCode());
            }

            Map<?, ?> resp = response.getBody();
            Object success = resp.get("success");
            if (!Boolean.TRUE.equals(success)) {
                Object message = resp.get("message");
                throw new RuntimeException(message != null ? message.toString() : "无可用 TURN 服务器");
            }

            String serverIp = stringVal(resp.get("serverIp"));
            if (serverIp == null || serverIp.isBlank()) {
                throw new RuntimeException("turn_router 返回 serverIp 为空");
            }

            int serverPort = intVal(resp.get("serverPort"), 3479);
            log.info("[TurnRouter] 调度成功 callId={} serverIp={} serverPort={} serverName={}",
                    callId, serverIp, serverPort, resp.get("serverName"));

            return TurnRouteResult.builder()
                    .serverId(stringVal(resp.get("serverId")))
                    .serverIp(serverIp)
                    .serverPort(serverPort)
                    .serverName(stringVal(resp.get("serverName")))
                    .build();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            log.error("[TurnRouter] 调度请求失败 url={} callId={}", url, callId, e);
            throw new RuntimeException("turn_router 调度失败: " + e.getMessage(), e);
        }
    }

    private static String trimTrailingSlash(String baseUrl) {
        String s = baseUrl.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    private static String stringVal(Object value) {
        return value == null ? null : value.toString();
    }

    private static int intVal(Object value, int defaultValue) {
        if (value instanceof Number n) {
            return n.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(value.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return defaultValue;
    }

    private RestTemplate buildRestTemplate() {
        WebRtcProperties.TurnRouter router = webRtcProperties.getTurnRouter();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.max(router.getConnectTimeoutMs(), 1_000));
        factory.setReadTimeout(Math.max(router.getReadTimeoutMs(), 1_000));
        return new RestTemplate(factory);
    }
}
