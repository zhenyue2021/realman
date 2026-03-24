package org.jeecg.modules.device.controller;

import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
     * EMQX HTTP Auth 认证回调
     * 返回 {"result":"allow"} 或 {"result":"deny"}
     */
    @PostMapping("/auth")
    public ResponseEntity<Map<String, String>> auth(@RequestBody Map<String, String> body) {
        log.info("[MqttAuth] requestBody={}", body);
        String clientId = body.get("clientid");
        String username = body.get("username");
        String password = body.get("password");
        String peerHost = body.get("peerhost");
        String peername = body.get("peername");

        // 平台服务账号直接放行
//        if (clientId != null && clientId.startsWith("iot-platform")) {
//            return allow();
//        }

        boolean ok = secretService.validateSecret(username, password, peerHost);
        log.info("[MqttAuth] clientId={} peerhost={} peername={} allow={}", clientId, peerHost, peername, ok);
        return ok ? allow() :deny();
    }

    /**
     * EMQX HTTP ACL 授权回调
     * 设备只能访问自身 device/{deviceCode}/... 命名空间下的Topic
     */
    @PostMapping("/acl")
    public ResponseEntity<Map<String, String>> acl(@RequestBody Map<String, String> body) {
        log.info("[MqttAcl] requestBody={}", body);
        String clientId = body.get("clientid");
        String username = body.get("username");
        String topic    = body.get("topic");

        if (clientId != null && clientId.startsWith("iot-platform")) return allow();

        boolean ok = secretService.validateAcl(username, topic);
        return ok ? allow() : deny();
    }


    private ResponseEntity<Map<String, String>> allow() {
        return ResponseEntity.ok(Map.of("result", "allow"));
    }

    private ResponseEntity<Map<String, String>> deny() {
        return ResponseEntity.ok(Map.of("result", "deny"));
    }
}
