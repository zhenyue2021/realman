package org.jeecg.modules.commhub.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.jeecg.modules.commhub.service.MqttAuthService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * EMQX HTTP 鉴权/ACL 回调，见 {@link MqttAuthService} 类注释里的 EMQX 侧配置约定。
 * 不经过对外 Gateway，也不使用 {@code Result} 包装——EMQX 要求的响应体是
 * {@code {"result":"allow"|"deny"}} 这个固定形状。
 */
@Hidden
@RestController
@RequiredArgsConstructor
@Tag(name = "EMQX 鉴权回调（内部）", description = "MQTT CONNECT 鉴权 + Topic 级 ACL")
public class MqttAuthController {

    private final MqttAuthService mqttAuthService;

    @PostMapping("/internal/mqtt/auth")
    @Operation(summary = "EMQX HTTP 鉴权回调")
    public Map<String, Object> auth(@RequestBody Map<String, String> body) {
        String clientId = textField(body, "clientid", "clientId");
        String username = textField(body, "username", "username");
        String password = textField(body, "password", "password");
        boolean allow = mqttAuthService.authenticate(clientId, username, password);
        return Map.of("result", allow ? "allow" : "deny");
    }

    @PostMapping("/internal/mqtt/acl")
    @Operation(summary = "EMQX HTTP ACL 回调")
    public Map<String, Object> acl(@RequestBody Map<String, String> body) {
        String clientId = textField(body, "clientid", "clientId");
        String username = textField(body, "username", "username");
        String topic = textField(body, "topic", "topic");
        String action = textField(body, "action", "action");
        boolean allow = mqttAuthService.authorize(clientId, username, topic, action);
        return Map.of("result", allow ? "allow" : "deny");
    }

    private static String textField(Map<String, String> body, String key1, String key2) {
        String value = body.get(key1);
        return value != null ? value : body.get(key2);
    }
}
