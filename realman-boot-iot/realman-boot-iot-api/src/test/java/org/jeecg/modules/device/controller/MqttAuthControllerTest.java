package org.jeecg.modules.device.controller;

import cn.hutool.crypto.digest.DigestUtil;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 使用 MockMvc 模拟 EMQX 对 HTTP 接口的真实调用，
 * 包括 MD5(deviceCode) 密码计算以及 peerhost 按 application-dev.yml 中 MQTT Broker IP 设置。
 *
 * 鉴权模型：
 *   clientid = deviceCode
 *   username = deviceCode
 *   password = MD5(deviceCode)
 */
@WebMvcTest(MqttAuthController.class)
@AutoConfigureMockMvc
public class MqttAuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceSecretService secretService;

    /**
     * EMQX 连接到平台的 IP（来自 application-dev.yml 中 mqtt.broker.url）
     */
    private static final String PEER_HOST = "172.16.44.66";

    /**
     * 设备首次连接 / 断线重连时，使用 password = MD5(deviceCode) 的正常鉴权流程
     */
    @Test
    void testAuthAllowWhenSecretValid() throws Exception {
        String deviceCode = "DEV001";
        String password = DigestUtil.md5Hex(deviceCode);

        when(secretService.validateSecret(eq(deviceCode), eq(password), any())).thenReturn(true);

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "password": "%s",
                  "peerhost": "%s"
                }
                """.formatted(deviceCode, deviceCode, password, PEER_HOST);

        mockMvc.perform(post("/internal/mqtt/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"));
    }

    /**
     * 设备首次连接 / 重连时，密码错误 → 拒绝连接
     */
    @Test
    void testAuthDenyWhenSecretInvalid() throws Exception {
        String deviceCode = "DEV001";
        String wrongPassword = "wrong-secret";

        when(secretService.validateSecret(eq(deviceCode), eq(wrongPassword), any())).thenReturn(false);

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "password": "%s",
                  "peerhost": "%s"
                }
                """.formatted(deviceCode, deviceCode, wrongPassword, PEER_HOST);

        mockMvc.perform(post("/internal/mqtt/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    /**
     * 平台服务账号（clientId 以 iot-platform 开头）应直接放行，不走密钥校验
     */
    @Test
    void testAuthAllowForPlatformClient() throws Exception {
        String clientId = "iot-platform-server";

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "password": "any-password",
                  "peerhost": "%s"
                }
                """.formatted(clientId, clientId, PEER_HOST);

        mockMvc.perform(post("/internal/mqtt/auth")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"));

        Mockito.verifyNoInteractions(secretService);
    }

    /**
     * ACL 校验：设备只能访问自身 device/{deviceCode}/... 命名空间
     */
    @Test
    void testAclAllowForSelfNamespace() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/status/report";

        when(secretService.validateAcl(deviceCode, topic)).thenReturn(true);

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "topic": "%s"
                }
                """.formatted(deviceCode, deviceCode, topic);

        mockMvc.perform(post("/internal/mqtt/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"));
    }

    /**
     * ACL 校验：越权访问其他设备 Topic 时应 deny
     */
    @Test
    void testAclDenyForOtherDeviceNamespace() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/OTHER_DEVICE/status/report";

        when(secretService.validateAcl(deviceCode, topic)).thenReturn(false);

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "topic": "%s"
                }
                """.formatted(deviceCode, deviceCode, topic);

        mockMvc.perform(post("/internal/mqtt/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("deny"));
    }

    /**
     * 平台服务账号在 ACL 中也应直接放行
     */
    @Test
    void testAclAllowForPlatformClient() throws Exception {
        String clientId = "iot-platform-job";
        String topic = "device/ANY/status/report";

        String body = """
                {
                  "clientid": "%s",
                  "username": "%s",
                  "topic": "%s"
                }
                """.formatted(clientId, clientId, topic);

        mockMvc.perform(post("/internal/mqtt/acl")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("allow"));

        Mockito.verifyNoInteractions(secretService);
    }
}

