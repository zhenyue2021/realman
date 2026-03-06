package org.jeecg.modules.device.security;

import cn.hutool.core.util.HexUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 设备密钥管理服务
 *
 * 鉴权模型（连接层鉴权，非应用层登录）：
 *   clientId = deviceCode | username = deviceCode | password = deviceSecret
 *   EMQX HTTP Auth 插件 → POST /internal/mqtt/auth → 本服务validateSecret()
 *   验证通过后MQTT连接建立，后续消息体中无需携带任何鉴权信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecretService {

    private final StringRedisTemplate redisTemplate;
    private final IotDeviceMapper deviceMapper;
    private static final long CACHE_HOURS = 7 * 24L;

    /** 新设备注册时自动生成64位Hex密钥 */
    public String generateSecret(IotDevice device) {
        byte[] b = new byte[32];
        new SecureRandom().nextBytes(b);
        String secret = HexUtil.encodeHexStr(b);
        device.setDeviceSecret(secret);
        device.setSecretCreateTime(LocalDateTime.now());
        deviceMapper.updateById(device);
        cache(device.getDeviceCode(), secret);
        log.info("[Secret] 设备[{}]密钥已生成", device.getDeviceCode());
        return secret;
    }

    /** 重置密钥（原密钥立即失效，需重新下发设备端） */
    public String resetSecret(String deviceCode) {
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode));
        if (device == null) throw new RuntimeException("设备不存在: " + deviceCode);
        evict(deviceCode);
        return generateSecret(device);
    }

    /** EMQX HTTP Auth 回调时调用 */
    public boolean validateSecret(String deviceCode, String secret) {
        if (deviceCode == null || secret == null) return false;
        // 优先查Redis缓存
        String cached = redisTemplate.opsForValue().get(
                DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + deviceCode);
        if (cached != null) return cached.equals(secret);
        // 降级查DB
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .ne(IotDevice::getStatus, DeviceConstant.DeviceStatus.DISABLED));
        if (device == null) { log.warn("[Secret] 设备[{}]不存在或已禁用", deviceCode); return false; }
        boolean ok = secret.equals(device.getDeviceSecret());
        if (ok) cache(deviceCode, secret);
        else log.warn("[Secret] 设备[{}]密钥不匹配", deviceCode);
        return ok;
    }

    /** ACL校验：设备只能访问自身topic命名空间 */
    public boolean validateAcl(String deviceCode, String topic) {
        boolean ok = topic != null && topic.startsWith("device/" + deviceCode + "/");
        if (!ok) log.warn("[ACL] 设备[{}]越权访问 topic={}", deviceCode, topic);
        return ok;
    }

    /** 禁用设备时调用，使缓存立即失效 */
    public void evict(String deviceCode) {
        redisTemplate.delete(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + deviceCode);
    }

    private void cache(String deviceCode, String secret) {
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + deviceCode,
                secret, CACHE_HOURS, TimeUnit.HOURS);
    }
}
