package org.jeecg.common.util;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.SecureRandom;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.PBEParameterSpec;

import lombok.extern.slf4j.Slf4j;

/**
 * PBE 密码工具类，与历史 Jeecg 存储格式兼容（{@value #ALGORITHM}）。
 * <p>
 * 说明：该算法为历史兼容保留；新业务若需高强度存储请使用专用密码哈希（如 Argon2/bcrypt）而非本类。
 */
@Slf4j
public final class PasswordUtil {

	private PasswordUtil() {
	}

	/**
	 * JAVA6 支持以下任意一种算法 PBEWITHMD5ANDDES PBEWITHMD5ANDTRIPLEDES
	 * PBEWITHSHAANDDESEDE PBEWITHSHA1ANDRC2_40 PBKDF2WITHHMACSHA1
	 */
	public static final String ALGORITHM = "PBEWithMD5AndDES";

	/** 与历史实现一致的默认盐字符串（8 字节，满足 PBE 盐长要求） */
	public static final String SALT = "63293188";

	private static final int ITERATION_COUNT = 1000;

	private static final HexFormat HEX = HexFormat.of();

	private static final SecureRandom SALT_RANDOM = new SecureRandom();

	/**
	 * 获取加密算法中使用的盐值；解密须与加密使用相同盐。盐长度须为 8 字节。
	 */
	public static byte[] getSalt() {
		return SALT_RANDOM.generateSeed(8);
	}

	public static byte[] getStaticSalt() {
		return SALT.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * 根据 PBE 密码生成密钥。
	 */
	private static Key getPbeKey(String password) {
		if (password == null) {
			throw new IllegalArgumentException("password must not be null");
		}
		try {
			SecretKeyFactory keyFactory = SecretKeyFactory.getInstance(ALGORITHM);
			PBEKeySpec keySpec = new PBEKeySpec(password.toCharArray());
			return keyFactory.generateSecret(keySpec);
		} catch (Exception e) {
			throw new IllegalStateException("Failed to derive PBE key", e);
		}
	}

	/**
	 * 加密明文字符串。
	 *
	 * @param plaintext 明文
	 * @param password  密钥口令
	 * @param salt      盐（与解密时须一致）
	 * @return 十六进制密文；参数非法或加密失败时返回 {@code null}
	 */
	public static String encrypt(String plaintext, String password, String salt) {
		if (plaintext == null || password == null || salt == null) {
			return null;
		}
		try {
			Key key = getPbeKey(password);
			PBEParameterSpec parameterSpec = new PBEParameterSpec(salt.getBytes(StandardCharsets.UTF_8), ITERATION_COUNT);
			// 算法名含 PBE 参数，与历史数据兼容；非标准 AES/GCM（Sonar 安全规则可忽略）
			Cipher cipher = Cipher.getInstance(ALGORITHM); // NOSONAR java:S5542
			cipher.init(Cipher.ENCRYPT_MODE, key, parameterSpec);
			// 中文用户名等场景须固定为 UTF-8，避免跨平台差异（gitee/issues/IZUD7）
			byte[] encipheredData = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
			return bytesToHexString(encipheredData);
		} catch (Exception e) {
			log.warn("Password encrypt failed: {}", e.toString());
			return null;
		}
	}

	/**
	 * 解密密文字符串。
	 *
	 * @param ciphertext 十六进制密文
	 * @param password   与加密时一致的口令
	 * @param salt       与加密时一致的盐
	 * @return 明文；失败或参数非法时返回 {@code null}
	 */
	public static String decrypt(String ciphertext, String password, String salt) {
		if (ciphertext == null || password == null || salt == null) {
			return null;
		}
		try {
			Key key = getPbeKey(password);
			PBEParameterSpec parameterSpec = new PBEParameterSpec(salt.getBytes(StandardCharsets.UTF_8), ITERATION_COUNT);
			Cipher cipher = Cipher.getInstance(ALGORITHM); // NOSONAR java:S5542
			cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
			byte[] raw = hexStringToBytes(ciphertext);
			if (raw.length == 0) {
				return null;
			}
			byte[] passDec = cipher.doFinal(raw);
			return new String(passDec, StandardCharsets.UTF_8);
		} catch (Exception e) {
			log.debug("Password decrypt failed: {}", e.toString());
			return null;
		}
	}

	/**
	 * 将字节数组转为十六进制字符串（小写，与历史 {@link Integer#toHexString} 行为一致）。
	 */
	public static String bytesToHexString(byte[] src) {
		if (src == null || src.length == 0) {
			return null;
		}
		return HEX.formatHex(src);
	}

	/**
	 * 将十六进制字符串转为字节数组；非法输入返回 {@code null}。
	 */
	public static byte[] hexStringToBytes(String hexString) {
		if (hexString == null || hexString.isEmpty()) {
			return new byte[0];
		}
		try {
			return HEX.parseHex(hexString.trim());
		} catch (IllegalArgumentException e) {
			log.warn("Invalid hex string: {}", e.getMessage());
			return new byte[0];
		}
	}

}
