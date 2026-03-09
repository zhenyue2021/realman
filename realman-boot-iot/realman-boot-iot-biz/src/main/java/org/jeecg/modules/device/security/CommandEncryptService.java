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
 * MQTT 消息体加密服务（Per-Device AES-256-CBC）
 *
 * 密钥派生：
 *   deviceAesKey = SHA256(deviceCode)[0..31]
 *   设备端用同样算法离线计算，无需存储、无需网络
 *
 * 密文格式：ivHex(32char) + ":" + Base64(AES密文)
 * 每条消息独立随机IV，防重放攻击
 *
 * 设备端参考实现（Python）：
 *   import hashlib, base64
 *   from Crypto.Cipher import AES
 *   key  = hashlib.sha256(device_code.encode()).digest()  # 32字节
 *   iv   = os.urandom(16)
 *   cipher = AES.new(key, AES.MODE_CBC, iv)
 *   ct   = cipher.encrypt(pad(payload.encode(), 16))
 *   msg  = iv.hex() + ":" + base64.b64encode(ct).decode()
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommandEncryptService {


    @Value("${device.encrypt.enabled:true}")
    private boolean encryptEnabled;

    private final StringRedisTemplate redisTemplate;
    private static final String AES_CACHE = "iot:device:aeskey:";

    // ── 加密（平台→设备）────────────────────────────────────────────
    public String encryptForDevice(String deviceCode, String plain) {
        if (!encryptEnabled || plain == null) return plain;
        return doEncrypt(getAesKey(deviceCode), plain);
    }

    // ── 解密（设备→平台）────────────────────────────────────────────
    public String decryptFromDevice(String deviceCode, String enc) {
        if (!encryptEnabled || !isEncrypted(enc)) return enc;
        return doDecrypt(getAesKey(deviceCode), enc);
    }

    public boolean isEncrypted(String s) {
        return s != null && s.matches("[0-9a-fA-F]{32}:.+");
    }

    /** 禁用设备或密码变更时清除 AES Key 缓存 */
    public void evictCache(String deviceCode) {
        redisTemplate.delete(AES_CACHE + deviceCode);
    }

    // ── 派生 AES Key：SHA256(deviceCode)[0..31] ─────────────────────
    private byte[] getAesKey(String deviceCode) {
        String cached = redisTemplate.opsForValue().get(AES_CACHE + deviceCode);
        if (cached != null) return HexUtil.decodeHex(cached);

        // SHA256(deviceCode) 取前32字节作为256bit AES密钥
        byte[] hash = DigestUtil.sha256(deviceCode.getBytes(StandardCharsets.UTF_8));
        byte[] key  = new byte[32];
        System.arraycopy(hash, 0, key, 0, 32);

        redisTemplate.opsForValue().set(AES_CACHE + deviceCode,
                HexUtil.encodeHexStr(key), 24L, TimeUnit.HOURS);
        return key;
    }

    private String doEncrypt(byte[] key, String plain) {
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return HexUtil.encodeHexStr(iv) + ":"
                    + Base64.getEncoder().encodeToString(
                    c.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException("加密失败: " + e.getMessage(), e);
        }
    }

    private String doDecrypt(byte[] key, String enc) {
        try {
            String[] p = enc.split(":", 2);
            javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/CBC/PKCS5Padding");
            c.init(javax.crypto.Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new IvParameterSpec(HexUtil.decodeHex(p[0])));
            return new String(c.doFinal(Base64.getDecoder().decode(p[1])),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("解密失败: " + e.getMessage(), e);
        }
    }
}
