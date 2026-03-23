package org.jeecg.modules.device.security;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.service.DeviceMqttConnectionAddressService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 设备密钥管理服务
 * <p>
 * 鉴权模型（连接层鉴权，非应用层登录）：
 * clientId = deviceCode | username = deviceCode | password = deviceSecret
 * EMQX HTTP Auth 插件 → POST /internal/mqtt/auth → 本服务validateSecret()
 * 验证通过后MQTT连接建立，后续消息体中无需携带任何鉴权信息
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeviceSecretService {

    private final StringRedisTemplate redisTemplate;
    private final IotDeviceMapper deviceMapper;
    private final DeviceMqttConnectionAddressService mqttConnectionAddressService;
    private static final long CACHE_HOURS = 24L;

    /**
     * 新设备注册时自动生成64位Hex密钥
     */
    public String generateSecret(@MonotonicNonNull String deviceCode) {
        String secret = DigestUtil.md5Hex(deviceCode);
        cache(deviceCode, secret);
        log.info("[Secret] 设备[{}]密钥已生成", deviceCode);
        return secret;
    }


    /**
     * EMQX HTTP Auth 回调时调用（无连接地址信息）
     */
    public boolean validateSecret(String deviceCode, String secret) {
        return validateSecret(deviceCode, secret, null);
    }

    /**
     * EMQX HTTP Auth 回调时调用
     *
     * @param mqttPeerHost EMQX 请求体中的 {@code peerhost}，鉴权成功后会异步写入 {@code iot_device.address}，不阻塞本方法
     */
    public boolean validateSecret(String deviceCode, String secret, String mqttPeerHost) {
        if (deviceCode == null || secret == null) return false;
        // 查询设备是否存在或被禁用
        IotDevice device = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .ne(IotDevice::getStatus, DeviceConstant.DeviceStatus.DISABLED));
        if (device == null) {
            log.warn("[Secret] 设备[{}]不存在或已禁用", deviceCode);
            return false;
        }
        // 计算期望密码比对
        boolean ok = secret.equals(device.getDeviceSecret());
        if (ok) {
            cache(deviceCode, secret);
            mqttConnectionAddressService.updateAddressAfterAuthSuccess(device.getId(), mqttPeerHost);
        } else {
            log.warn("[Secret] 设备[{}]密钥不匹配", deviceCode);
        }
        return ok;
    }

    /**
     * ACL 校验：设备只能访问自身 Topic 命名空间
     *
     * <p>允许的 Topic 格式：
     * <ul>
     *   <li>{@code device/{deviceCode}/...}   — 标准设备业务 Topic（配置/指令/OTA 等）</li>
     *   <li>{@code {deviceCode}/master/...}   — 主控设备主动上报（如 cmd / states / rtsp/ctrl）</li>
     *   <li>{@code {deviceCode}/slave/...}    — 机器人设备主动上报（如 cmd / states）</li>
     * </ul>
     */
    public boolean validateAcl(String deviceCode, String topic) {
        if (topic == null || deviceCode == null) {
            log.warn("[ACL]-1 设备[{}]越权访问 topic={}", deviceCode, topic);
            return false;
        }
        boolean ok = topic.startsWith("device/" + deviceCode + "/")
                || topic.startsWith("/"+deviceCode + "/master/")
                || topic.startsWith("/"+deviceCode + "/slave/");
        if (!ok) log.warn("[ACL]-1 设备[{}]越权访问 topic={}", deviceCode, topic);
        return ok;
    }

    /**
     * 禁用设备时调用，使缓存立即失效
     */
    public void evict(String deviceCode) {
        redisTemplate.delete(DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + deviceCode);
    }

    private void cache(String deviceCode, String secret) {
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.DEVICE_SECRET_PREFIX + deviceCode,
                secret, CACHE_HOURS, TimeUnit.HOURS);
    }
}
