package org.jeecg.modules.device.service.signaling;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.digest.DigestUtil;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.config.WebRtcProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/**
 * 信令服务器房间密钥管理（会话级：每次 WebRTC 会话生成并推送到调度返回的信令节点）。
 */
@Slf4j
@Service
@RefreshScope
public class SignalingKeyService {

    private static final String KEY_API_PATH = "/api/set_key";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WebRtcProperties webRtcProperties;
    private final RestTemplate restTemplate;

    public SignalingKeyService(WebRtcProperties webRtcProperties) {
        this.webRtcProperties = webRtcProperties;
        this.restTemplate = buildRestTemplate();
    }

    /**
     * 生成密钥并推送到指定信令服务器，成功则返回密钥。
     *
     * @throws RuntimeException 推送失败时
     */
    public String generateAndPushSessionKey(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            throw new RuntimeException("信令服务器 IP 为空，无法推送密钥");
        }
        String key = generateKey();
        if (!pushToServer(serverIp, key)) {
            throw new RuntimeException("信令密钥推送失败: serverIp=" + serverIp);
        }
        return key;
    }

    /**
     * 返回信令 WebSocket URL（供 WebRTC 指令填充 signalUrl 字段）。
     */
    public String buildSignalUrl(String serverIp) {
        if (serverIp == null || serverIp.isBlank()) {
            return null;
        }
        int port = webRtcProperties.getSignaling().getServer().getPort();
        return "ws://" + serverIp + ":" + port;
    }

    static String generateKey() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        String timestampStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssS"));
        String randomHex = HexUtil.encodeHexStr(bytes);
        return timestampStr + "_" + DigestUtil.md5Hex(randomHex).toUpperCase(Locale.ROOT);
    }

    private boolean pushToServer(String serverIp, String key) {
        int port = webRtcProperties.getSignaling().getServer().getPort();
        String url = "http://" + serverIp + ":" + port + KEY_API_PATH;
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> request = new HttpEntity<>(
                    Map.of("room_key", key), headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    url, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Object success = response.getBody().get("success");
                if (Boolean.TRUE.equals(success)) {
                    log.info("[Signaling] 推送响应 success=TRUE url={}", url);
                    return true;
                }
                log.warn("[Signaling] 推送响应 success=false url={} body={}", url, response.getBody());
            } else {
                log.warn("[Signaling] 推送响应非 2xx url={} status={}", url, response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("[Signaling] 推送密钥异常 url={}", url, e);
        }
        return false;
    }

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return new RestTemplate(factory);
    }
}
