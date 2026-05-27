package org.jeecg.modules.device.security;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.DeviceMqttConnectionAddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceSecretServiceTest {

    private static final String DEVICE_CODE = "DEV001";
    private static final String SECRET = "secret-abc";

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private IotDeviceMapper deviceMapper;
    private DeviceMqttConnectionAddressService addressService;
    private DeviceSecretService service;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        addressService = Mockito.mock(DeviceMqttConnectionAddressService.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new DeviceSecretService(redisTemplate, deviceMapper, addressService);
    }

    @Test
    @DisplayName("缓存命中且密钥正确：鉴权通过，不查 DB")
    void validateSecretUsesCacheWhenHit() {
        when(valueOps.get(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE)).thenReturn(SECRET);

        boolean ok = service.validateSecret(DEVICE_CODE, SECRET);

        assertThat(ok).isTrue();
        verify(deviceMapper, never()).selectOne(any(LambdaQueryWrapper.class));
        verify(addressService, never()).updateAddressAfterAuthSuccess(any(), any());
    }

    @Test
    @DisplayName("缓存命中但密钥错误：拒绝，不查 DB")
    void validateSecretRejectsWrongSecretFromCache() {
        when(valueOps.get(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE)).thenReturn(SECRET);

        boolean ok = service.validateSecret(DEVICE_CODE, "wrong");

        assertThat(ok).isFalse();
        verify(deviceMapper, never()).selectOne(any(LambdaQueryWrapper.class));
    }

    @Test
    @DisplayName("缓存未命中：查 DB 成功后回填 Redis")
    void validateSecretLoadsDbAndCachesOnMiss() {
        when(valueOps.get(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE)).thenReturn(null);
        IotDevice device = activeDevice("id1", SECRET);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(device);

        boolean ok = service.validateSecret(DEVICE_CODE, SECRET);

        assertThat(ok).isTrue();
        verify(valueOps).set(
                eq(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE),
                eq(SECRET),
                eq(24L),
                eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("缓存未命中且设备禁用/不存在：拒绝")
    void validateSecretRejectsWhenDeviceMissing() {
        when(valueOps.get(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE)).thenReturn(null);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        boolean ok = service.validateSecret(DEVICE_CODE, SECRET);

        assertThat(ok).isFalse();
        verify(valueOps, never()).set(any(), any(), any(Long.class), any(TimeUnit.class));
    }

    @Test
    @DisplayName("缓存命中且带 peerhost：异步更新地址需解析 deviceId")
    void validateSecretUpdatesAddressOnCacheHitWithPeerHost() {
        when(valueOps.get(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE)).thenReturn(SECRET);
        IotDevice idOnly = new IotDevice();
        idOnly.setId("id1");
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(idOnly);

        boolean ok = service.validateSecret(DEVICE_CODE, SECRET, "192.168.1.10");

        assertThat(ok).isTrue();
        verify(addressService).updateAddressAfterAuthSuccess("id1", "192.168.1.10");
    }

    @Test
    @DisplayName("禁用设备时 evict 删除缓存 Key")
    void evictDeletesSecretCache() {
        service.evict(DEVICE_CODE);
        verify(redisTemplate).delete(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE);
    }

    @Test
    @DisplayName("注册 generateSecret 写入 Redis")
    void generateSecretCachesValue() {
        ArgumentCaptor<String> secretCaptor = ArgumentCaptor.forClass(String.class);

        String secret = service.generateSecret(DEVICE_CODE);

        assertThat(secret).isNotBlank();
        verify(valueOps).set(
                eq(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + DEVICE_CODE),
                secretCaptor.capture(),
                eq(24L),
                eq(TimeUnit.HOURS));
        assertThat(secretCaptor.getValue()).isEqualTo(secret);
    }

    private static IotDevice activeDevice(String id, String secret) {
        IotDevice device = new IotDevice();
        device.setId(id);
        device.setDeviceCode(DEVICE_CODE);
        device.setDeviceSecret(secret);
        device.setStatus(DeviceConstant.DeviceStatus.ONLINE);
        return device;
    }
}
