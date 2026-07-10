package org.jeecg.modules.ota.service.impl;

import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;
import org.jeecg.modules.deviceinfo.contract.dto.DeviceInfoDTO;
import org.jeecg.modules.ota.mapper.OtaTaskDeviceMapper;
import org.jeecg.modules.ota.service.IOtaKeyService;
import org.jeecg.modules.ota.service.IOtaSystemSettingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 资源前置校验优先走 PRD 9.7.4 的主动探测（{@code ota/resource-probe}，publish-and-wait），
 * 探测成功时不依赖心跳新鲜度；探测超时/不支持时按心跳快照回退降级，且仍需满足各
 * valid_seconds 新鲜度要求。此前该 Topic 常量已定义但从未被任何代码实际调用，是真实
 * 遗漏而非"设计如此"，见 {@link OtaPrecheckServiceImpl#probeResourcesActively} 注释。
 */
@ExtendWith(MockitoExtension.class)
class OtaPrecheckServiceImplTest {

    @Mock
    private OtaTaskDeviceMapper taskDeviceMapper;
    @Mock
    private IOtaKeyService keyService;
    @Mock
    private IOtaSystemSettingService systemSettingService;
    @Mock
    private CommHubFeignClient commHubFeignClient;

    private OtaPrecheckServiceImpl precheckService;

    @BeforeEach
    void setUp() {
        precheckService = new OtaPrecheckServiceImpl(taskDeviceMapper, keyService, systemSettingService, commHubFeignClient);
    }

    private DeviceInfoDTO deviceWithHeartbeatSnapshot() {
        DeviceInfoDTO device = new DeviceInfoDTO();
        device.setDeviceId("device-1");
        device.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(10));
        device.setResourceSnapshot(Map.of(
                "disk_available_mb", 10240,
                "power_status", "normal",
                "memory_available_mb", 1024,
                "network_reachable", true));
        return device;
    }

    private Map<String, Object> validResourceMap() {
        return Map.of(
                "disk_available_mb", 20480,
                "power_status", "normal",
                "memory_available_mb", 2048,
                "network_reachable", true);
    }

    @SuppressWarnings("unchecked")
    private void stubProbeResult(String status, Map<String, Object> ackPayload) {
        MqttPublishResult publishResult = new MqttPublishResult();
        publishResult.setStatus(status);
        publishResult.setAckPayload(ackPayload);
        when(commHubFeignClient.publish(any())).thenReturn(Result.ok(publishResult));
    }

    @Test
    void checkResources_shouldSkipHeartbeatFreshnessWhenActiveProbeSucceeds() {
        DeviceInfoDTO device = deviceWithHeartbeatSnapshot();
        stubProbeResult("ACKED", validResourceMap());

        assertThatNoException().isThrownBy(() -> precheckService.checkResources(device));

        // 主动探测成功即视为"此刻"的数据，不应再去读 valid_seconds 系统设置做新鲜度判断
        verify(systemSettingService, org.mockito.Mockito.never()).getLong(any());
    }

    @Test
    void checkResources_shouldSendWaitAckRequestToResourceProbeTopic() {
        DeviceInfoDTO device = deviceWithHeartbeatSnapshot();
        stubProbeResult("ACKED", validResourceMap());

        precheckService.checkResources(device);

        ArgumentCaptor<MqttPublishRequest> captor = ArgumentCaptor.forClass(MqttPublishRequest.class);
        verify(commHubFeignClient).publish(captor.capture());
        MqttPublishRequest request = captor.getValue();
        assertThat(request.getTopicSuffix()).isEqualTo("ota/resource-probe");
        assertThat(request.isWaitAck()).isTrue();
        assertThat(request.getDeviceId()).isEqualTo("device-1");
    }

    @Test
    void checkResources_shouldFallBackToHeartbeatSnapshotWhenProbeTimesOut() {
        DeviceInfoDTO device = deviceWithHeartbeatSnapshot();
        stubProbeResult("TIMEOUT", null);
        when(systemSettingService.getLong(any())).thenReturn(300L);

        assertThatNoException().isThrownBy(() -> precheckService.checkResources(device));
    }

    @Test
    void checkResources_shouldFallBackAndRejectWhenHeartbeatSnapshotStale() {
        DeviceInfoDTO device = deviceWithHeartbeatSnapshot();
        device.setLastHeartbeatAt(LocalDateTime.now().minusSeconds(600));
        stubProbeResult("TIMEOUT", null);
        when(systemSettingService.getLong(any())).thenReturn(300L);

        assertThatThrownBy(() -> precheckService.checkResources(device))
                .hasMessageContaining("ERR_RESOURCE_INSUFFICIENT");
    }

    @Test
    void checkResources_shouldFallBackWhenCommHubCallThrows() {
        DeviceInfoDTO device = deviceWithHeartbeatSnapshot();
        when(commHubFeignClient.publish(any())).thenThrow(new RuntimeException("comm-hub unreachable"));
        when(systemSettingService.getLong(any())).thenReturn(300L);

        assertThatNoException().isThrownBy(() -> precheckService.checkResources(device));
    }
}
