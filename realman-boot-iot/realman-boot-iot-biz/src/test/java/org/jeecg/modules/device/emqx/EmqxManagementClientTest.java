package org.jeecg.modules.device.emqx;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmqxManagementClientTest {

    @Test
    @DisplayName("从 tcp broker.url 推导 EMQX API 地址")
    void deriveApiUrlFromTcpBroker() {
        assertThat(EmqxManagementClient.deriveApiUrlFromBroker("tcp://realman-emqx:1883"))
                .isEqualTo("http://realman-emqx:18083");
        assertThat(EmqxManagementClient.deriveApiUrlFromBroker("tcp://172.16.44.66:1883"))
                .isEqualTo("http://172.16.44.66:18083");
    }

    @Test
    @DisplayName("无法推导时返回 null")
    void deriveApiUrlReturnsNullForUnknownScheme() {
        assertThat(EmqxManagementClient.deriveApiUrlFromBroker("ws://host:8083/mqtt")).isNull();
    }
}
