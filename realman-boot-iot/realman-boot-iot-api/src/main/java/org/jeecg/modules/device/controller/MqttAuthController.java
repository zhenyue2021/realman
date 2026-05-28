package org.jeecg.modules.device.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.config.shiro.IgnoreAuth;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * EMQX HTTP Auth / ACL 回调接口（仅供 EMQX 内部调用）
 *
 * 鉴权规则：
 *   设备 MQTT 密码 = MD5(deviceCode)，32位小写Hex
 *   设备端无需存储，启动时动态计算
 *   鉴权仅在首次连接/断线重连时触发，连接建立后消息收发不再鉴权
 *
 * EMQX 配置（emqx.conf）：
 *   authentication {
 *     backend = http; mechanism = password_based; method = post
 *     url = http://平台IP:8085/realman-iot/internal/mqtt/auth
 *     body { clientid="${clientid}", username="${username}", password="${password}", peerhost="${peerhost}" }
 *   }
 *   authorization {
 *     sources = [{
 *       type = http; method = post
 *       url = http://平台IP:8085/realman-iot/internal/mqtt/acl
 *       body { clientid="${clientid}", username="${username}", topic="${topic}", action="${action}" }
 *     }]
 *   }
 *
 * 安全说明：建议通过 Nginx IP 白名单限制此接口仅允许 EMQX 节点访问
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/mqtt")
@RequiredArgsConstructor
public class MqttAuthController {

    private final DeviceSecretService secretService;
    /**
     * EMQX HTTP Auth 认证回调。
     * <p>
     * 平台账号需 {@code is_superuser=true}，否则 EMQX 拒绝订阅 {@code $SYS/#}（SUBACK reasonCode=135）。
     */
    @IgnoreAuth
    @PostMapping("/auth")
    public ResponseEntity<Map<String, Object>> auth(@RequestBody Map<String, String> body) {
        log.info("[MqttAuth] requestBody={}", body);
        String clientId = textField(body, "clientid", "clientId");
        String username = textField(body, "username");
        String password = textField(body, "password");
        String peerHost = textField(body, "peerhost", "peerHost");
        String peername = textField(body, "peername", "peerName");

        if (isPlatformAccount(clientId, username)) {
            log.info("[MqttAuth] 平台账号放行(superuser): clientId={} username={}", clientId, username);
            return allowPlatformSuperuser();
        }

        boolean ok = secretService.validateSecret(username, password, peerHost);
        log.info("[MqttAuth] clientId={} peerhost={} peername={} allow={}", clientId, peerHost, peername, ok);
        return ok ? allow() : deny();
    }

    /**
     * EMQX HTTP ACL 授权回调
     * 设备只能访问自身 device/{deviceCode}/... 命名空间下的Topic
     */
    @IgnoreAuth
    @PostMapping("/acl")
    public ResponseEntity<Map<String, Object>> acl(@RequestBody Map<String, String> body) {
        log.info("[MqttAcl] requestBody={}", body);
        String clientId = textField(body, "clientid", "clientId");
        String username = textField(body, "username");
        String topic    = textField(body, "topic");
        String action   = textField(body, "action");

        if (isPlatformAccount(clientId, username)) {
            log.debug("[MqttAcl] 平台账号放行: clientId={} username={} topic={} action={}",
                    clientId, username, topic, action);
            return allow();
        }

        boolean ok = secretService.validateAcl(username, topic);
        return ok ? allow() : deny();
    }


    /**
     * 平台 MQTT 客户端：superuser 才能订阅 $SYS/#（EMQX SUBACK 135 = Not authorized）
     */

    private static boolean isPlatformAccount(String clientId, String username) {
        return (clientId != null && clientId.startsWith("iot-platform"))
                || (username != null && username.startsWith("iot-platform"));
    }

    private ResponseEntity<Map<String, Object>> allowPlatformSuperuser() {
        Map<String, Object> body = new HashMap<>(4);
        body.put("result", "allow");
        body.put("is_superuser", true);
        // EMQX 5.8+：HTTP Auth 响应内嵌 ACL，兜底 $SYS 订阅（内置库先于 HTTP 匹配时仍可能不生效）
        body.put("acl", List.of(
                Map.of("permission", "allow", "action", "subscribe", "topic", "$SYS/#"),
                Map.of("permission", "allow", "action", "all", "topic", "#")
        ));
        return ResponseEntity.ok(body);
    }


    private ResponseEntity<Map<String, Object>> allow() {
        return ResponseEntity.ok(Map.of("result", "allow"));
    }

    private ResponseEntity<Map<String, Object>> deny() {
        return ResponseEntity.ok(Map.of("result", "deny"));
    }

    /** 兼容 EMQX 模板 {@code clientid} 与 Dashboard 常见写法 {@code clientId}。 */
    private static String textField(Map<String, String> body, String... keys) {
        for (String key : keys) {
            String value = body.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
