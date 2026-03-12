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

    /**
     * 加密（平台 → 设备方向）
     *
     * <p>生成随机 IV（16字节），执行 AES-256-CBC 加密，返回 ivHex:Base64(密文) 格式。
     * 若加密未启用（{@code device.encrypt.enabled=false}）则返回明文（仅用于本地调试）。
     *
     * @param deviceCode 设备编号（用于派生 AES 密钥）
     * @param plain      明文 JSON 字符串
     * @return 密文（ivHex:Base64），或原文（加密关闭时）
     */
    public String encryptForDevice(String deviceCode, String plain) {
        log.info("[CommandEncryptService] -1 encryptEnabled: {}", encryptEnabled);
        if (!encryptEnabled || plain == null) return plain;
        return doEncrypt(getAesKey(deviceCode), plain);
    }

    /**
     * 解密（设备 → 平台方向）
     *
     * <p>若输入不符合密文格式（ivHex:Base64），则视为明文直接返回（兼容加密关闭场景）。
     *
     * @param deviceCode 设备编号（用于派生 AES 密钥）
     * @param enc        密文（ivHex:Base64）或明文 JSON
     * @return 解密后的明文 JSON 字符串
     */
    public String decryptFromDevice(String deviceCode, String enc) {
        if (!encryptEnabled || !isEncrypted(enc)) return enc;
        return doDecrypt(getAesKey(deviceCode), enc);
    }

    public static void main(String[] args) {
        String plain = "e2138137600c5a683df1e944316c3c24:oo8r90tOoP4fS5I9GMPHvmUTnlANpiDfS1hZuzaEb3BmyL5bmWFIqBj8WuFoZdA0CGMYGcexsWHBSHwZHBxqgr761PK3GdHbY7v9EpHdrMucpgv7m85s7Osh46xnkhHV";
    }

    /**
     * 判断字符串是否符合密文格式：32位十六进制 IV + ":" + Base64内容
     *
     * @param s 待判断字符串
     * @return true=密文格式，false=明文或 null
     */
    public boolean isEncrypted(String s) {
        return s != null && s.matches("[0-9a-fA-F]{32}:.+");
    }

    /**
     * 清除设备 AES Key 缓存（禁用设备或 deviceCode 变更时调用）
     *
     * <p>缓存失效后，下次加解密操作将重新派生密钥并写入缓存。
     *
     * @param deviceCode 设备编号
     */
    public void evictCache(String deviceCode) {
        redisTemplate.delete(AES_CACHE + deviceCode);
    }

    /**
     * 派生设备 AES Key：SHA256(deviceCode) 取前 32 字节（256 bit）
     *
     * <p>密钥由 deviceCode 确定性派生，设备端用相同算法离线计算，无需网络传输密钥。
     * 结果缓存到 Redis 24h，减少重复计算。
     *
     * @param deviceCode 设备编号
     * @return 32 字节 AES 密钥
     */
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

    /**
     * AES-256-CBC 加密
     *
     * <p>每次调用生成独立随机 IV（16字节），防止相同明文产生相同密文（防重放）。
     * 输出格式：{ivHex(32字符)}:{Base64(AES-CBC加密结果)}
     *
     * @param key   32字节 AES 密钥
     * @param plain 明文字符串
     * @return 密文字符串
     */
    private String doEncrypt(byte[] key, String plain) {
        try {
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);  // 每条消息独立随机 IV
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

    /**
     * AES-256-CBC 解密
     *
     * <p>从密文字符串中分离 IV 和密文，执行解密。
     * 密文格式：{ivHex(32字符)}:{Base64(密文)}（与 doEncrypt 输出格式对应）
     *
     * @param key 32字节 AES 密钥
     * @param enc 密文字符串（ivHex:Base64格式）
     * @return 解密后的明文字符串
     */
    private String doDecrypt(byte[] key, String enc) {
        try {
            String[] p = enc.split(":", 2);  // p[0]=ivHex, p[1]=Base64密文
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
