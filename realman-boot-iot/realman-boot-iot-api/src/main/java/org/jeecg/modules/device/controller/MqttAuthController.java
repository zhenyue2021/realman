package org.jeecg.modules.device.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * EMQX HTTP Auth/ACL 回调接口
 *
 * 设备鉴权流程：
 *   设备连接EMQX时携带：clientId=deviceCode, username=deviceCode, password=deviceSecret
 *   EMQX HTTP Auth插件 → POST /internal/mqtt/auth → 本接口验证deviceSecret
 *   验证通过后MQTT连接建立，后续消息体中无需携带任何鉴权信息
 *
 * EMQX配置示例（emqx.conf）：
 *   authentication {
 *     backend = http; mechanism = password_based; method = post
 *     url = http://platform-host:8085/device-mgmt/internal/mqtt/auth
 *     body { clientid="${clientid}", username="${username}", password="${password}" }
 *   }
 *   authorization {
 *     sources = [{ type=http; method=post
 *       url = http://platform-host:8085/device-mgmt/internal/mqtt/acl
 *       body { clientid="${clientid}", username="${username}", topic="${topic}", action="${action}" }
 *     }]
 *   }
 *
 * 安全说明：此接口仅供EMQX内部回调，建议通过网络隔离/Nginx IP白名单限制访问来源
 */
@Slf4j
@Hidden
@RestController
@RequestMapping("/internal/mqtt")
@RequiredArgsConstructor
public class MqttAuthController {

    private final DeviceSecretService secretService;

    /**
     * EMQX HTTP Auth 认证回调
     * 返回 {"result":"allow"} 或 {"result":"deny"}
     */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> auth(@RequestBody Map<String, String> body) {
        String clientId = body.get("clientid");
        String username = body.get("username");
        String password = body.get("password");
        String peerHost = body.get("peerhost");

        // 平台服务账号直接放行
        if (clientId != null && clientId.startsWith("iot-platform")) {
            return ok("allow");
        }

        boolean allowed = secretService.validateSecret(username, password);
        log.info("[MqttAuth] clientId={} ip={} result={}", clientId, peerHost, allowed ? "allow" : "deny");
        return ok(allowed ? "allow" : "deny");
    }

    /**
     * EMQX HTTP ACL 授权回调
     * 设备只能访问自身 device/{deviceCode}/... 命名空间下的Topic
     */
    @PostMapping("/acl")
    public ResponseEntity<Map<String, String>> acl(@RequestBody Map<String, String> body) {
        String clientId = body.get("clientid");
        String username = body.get("username");
        String topic    = body.get("topic");
        String action   = body.get("action");

        if (clientId != null && clientId.startsWith("iot-platform")) return ok("allow");

        boolean allowed = secretService.validateAcl(username, topic);
        return ok(allowed ? "allow" : "deny");
    }

    private ResponseEntity<Map<String, String>> ok(String result) {
        return ResponseEntity.ok(Map.of("result", result));
    }
}
