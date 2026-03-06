package org.jeecg.modules.device.security;

import cn.hutool.core.util.HexUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * MQTT消息加密服务（Per-Device AES-256-CBC）
 *
 * 密钥派生：deviceAesKey = SHA256(masterKey + ":" + deviceSecret)[0..31]
 * 密文格式：ivHex(32char) + ":" + Base64(AES-256-CBC密文)
 * 每条消息独立随机IV，防重放攻击
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandEncryptService {

    @Value("${device.encrypt.master-key:iot-platform-master-key-32bytes!!}")
    private String masterKey;

    @Value("${device.encrypt.enabled:true}")
    private boolean encryptEnabled;

    private final IotDeviceMapper deviceMapper;
    private final StringRedisTemplate redisTemplate;
    private static final String AES_CACHE = "iot:device:aeskey:";

    public String encryptForDevice(String deviceCode, String plain) {
        if (!encryptEnabled || plain == null) return plain;
        return doEncrypt(deriveKey(deviceCode), plain);
    }

    public String decryptFromDevice(String deviceCode, String enc) {
        if (!encryptEnabled || !isEncrypted(enc)) return enc;
        return doDecrypt(deriveKey(deviceCode), enc);
    }

    public boolean isEncrypted(String s) {
        return s != null && s.matches("[0-9a-fA-F]{32}:.+");
    }

    public void evictCache(String deviceCode) {
        redisTemplate.delete(AES_CACHE + deviceCode);
    }

    private byte[] deriveKey(String deviceCode) {
        String cached = redisTemplate.opsForValue().get(AES_CACHE + deviceCode);
        if (cached != null) return HexUtil.decodeHex(cached);
        IotDevice dev = deviceMapper.selectOne(new LambdaQueryWrapper<IotDevice>()
                .eq(IotDevice::getDeviceCode, deviceCode)
                .select(IotDevice::getDeviceSecret));
        if (dev == null || dev.getDeviceSecret() == null)
            throw new RuntimeException("设备[" + deviceCode + "]密钥不存在");
        byte[] hash = DigestUtil.sha256((masterKey + ":" + dev.getDeviceSecret())
                .getBytes(StandardCharsets.UTF_8));
        byte[] key = new byte[32];
        System.arraycopy(hash, 0, key, 0, 32);
        redisTemplate.opsForValue().set(AES_CACHE + deviceCode,
                HexUtil.encodeHexStr(key), 7 * 24L, TimeUnit.HOURS);
        return key;
    }

    private String doEncrypt(byte[] key, String plain) {
        try {
            byte[] iv = new byte[16]; new SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return HexUtil.encodeHexStr(iv) + ":" +
                    Base64.getEncoder().encodeToString(c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new RuntimeException("加密失败", e); }
    }

    private String doDecrypt(byte[] key, String enc) {
        try {
            String[] p = enc.split(":", 2);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(HexUtil.decodeHex(p[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(p[1])), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new RuntimeException("解密失败", e); }
    }
}
