package org.jeecg.modules.device.emqx;

import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.mqtt.handler.DeviceOnlineOfflineHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceOnlineReconcileServiceTest {

    private EmqxManagementClient emqxManagementClient;
    private DeviceOnlineOfflineHandler onlineOfflineHandler;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private DeviceOnlineReconcileService reconcileService;

    @BeforeEach
    void setUp() {
        emqxManagementClient = Mockito.mock(EmqxManagementClient.class);
        onlineOfflineHandler = Mockito.mock(DeviceOnlineOfflineHandler.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        reconcileService = new DeviceOnlineReconcileService(
                emqxManagementClient, onlineOfflineHandler, redisTemplate);
        ReflectionTestUtils.setField(reconcileService, "reconcileEnabled", true);
        ReflectionTestUtils.setField(reconcileService, "reconcileLockSeconds", 120L);
    }

    @Test
    @DisplayName("持锁成功时对 EMQX connected 设备执行 reconcileOnline")
    void reconcileOnStartupWhenLockAcquired() {
        when(valueOps.setIfAbsent(
                eq(DeviceConstant.RedisKey.EMQX_STARTUP_RECONCILE_LOCK),
                anyString(), eq(120L), eq(TimeUnit.SECONDS))).thenReturn(true);
        when(emqxManagementClient.listConnectedDeviceCodes()).thenReturn(List.of("DEV001", "DEV002"));
        when(onlineOfflineHandler.reconcileOnline("DEV001"))
                .thenReturn(DeviceOnlineOfflineHandler.ReconcileOnlineResult.PROMOTED);
        when(onlineOfflineHandler.reconcileOnline("DEV002"))
                .thenReturn(DeviceOnlineOfflineHandler.ReconcileOnlineResult.ALREADY_ONLINE);

        reconcileService.reconcileOnStartup();

        verify(onlineOfflineHandler).reconcileOnline("DEV001");
        verify(onlineOfflineHandler).reconcileOnline("DEV002");
    }

    @Test
    @DisplayName("未获得启动锁时跳过对账")
    void skipWhenLockNotAcquired() {
        when(valueOps.setIfAbsent(anyString(), anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        reconcileService.reconcileOnStartup();

        verify(emqxManagementClient, never()).listConnectedDeviceCodes();
    }
}
