package org.jeecg.modules.commhub.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.config.MqttClientProperties;
import org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceAclRuleDTO;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationRequest;
import org.jeecg.modules.devicemgmt.contract.dto.DeviceSecretValidationResult;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * EMQX HTTP 鉴权/ACL 回调的业务实现。EMQX 侧配置约定（对齐既有 realman-boot-iot
 * 的做法，独立实现）：
 * <pre>
 * authentication { backend=http; method=post; url=http://comm-hub:8092/realman-comm-hub/internal/mqtt/auth
 *   body { clientid="${clientid}", username="${username}", password="${password}", peerhost="${peerhost}" } }
 * authorization { sources=[{ type=http; method=post; url=http://comm-hub:8092/realman-comm-hub/internal/mqtt/acl
 *   body { clientid="${clientid}", username="${username}", topic="${topic}", action="${action}" } }] }
 * </pre>
 * 设备侧：username/clientid = deviceCode，password = deviceSecret（见 {@code DeviceMgmtFeignClient#validateSecret}）。
 * 本服务自身的 MQTT 客户端账号（{@link MqttClientProperties#getUsername()}）按平台超管放行，
 * 授予 {@code $SYS/#} 订阅与全量 Topic 权限。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MqttAuthService {

    private final DeviceMgmtFeignClient deviceMgmtFeignClient;
    private final MqttClientProperties properties;

    public boolean authenticate(String clientId, String username, String password) {
        String identity = username != null ? username : clientId;
        if (isPlatformServiceAccount(identity, password)) {
            return true;
        }
        try {
            DeviceSecretValidationRequest request = new DeviceSecretValidationRequest();
            request.setDeviceCode(identity);
            request.setDeviceSecret(password);
            Result<DeviceSecretValidationResult> result = deviceMgmtFeignClient.validateSecret(request);
            return result != null && result.isSuccess() && result.getResult() != null && result.getResult().isAllow();
        } catch (Exception e) {
            log.warn("[comm-hub] MQTT 鉴权回调调用设备管理业务平台失败 identity={}: {}", identity, e.getMessage());
            return false;
        }
    }

    public boolean authorize(String clientId, String username, String topic, String action) {
        String identity = username != null ? username : clientId;
        if (isPlatformServiceAccount(identity, null)) {
            return true;
        }
        try {
            Result<List<DeviceAclRuleDTO>> result = deviceMgmtFeignClient.getAclRules(identity);
            if (result == null || !result.isSuccess() || result.getResult() == null) {
                return false;
            }
            return result.getResult().stream().anyMatch(rule -> matches(rule, topic, action));
        } catch (Exception e) {
            log.warn("[comm-hub] MQTT ACL 回调调用设备管理业务平台失败 identity={}: {}", identity, e.getMessage());
            return false;
        }
    }

    /** 平台服务账号（本服务自身的 MQTT 客户端）判定；仅在鉴权阶段校验密码，ACL 阶段用户名匹配即视为已鉴权通过。 */
    private boolean isPlatformServiceAccount(String identity, String password) {
        boolean identityMatches = properties.getUsername().equals(identity);
        return identityMatches && (password == null || properties.getPassword().equals(password));
    }

    private boolean matches(DeviceAclRuleDTO rule, String topic, String action) {
        if (!actionMatches(rule.getAction(), action)) {
            return false;
        }
        String pattern = rule.getTopicPattern();
        if (pattern == null) {
            return false;
        }
        if (pattern.endsWith("#")) {
            return topic.startsWith(pattern.substring(0, pattern.length() - 1));
        }
        return pattern.equals(topic);
    }

    private boolean actionMatches(String ruleAction, String requestedAction) {
        if (ruleAction == null || requestedAction == null) {
            return false;
        }
        return "ALL".equalsIgnoreCase(ruleAction) || ruleAction.equalsIgnoreCase(requestedAction);
    }
}
