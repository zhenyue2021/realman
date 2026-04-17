package org.jeecg.common.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import lombok.extern.slf4j.Slf4j;

/**
 * MD5 摘要工具（输出小写十六进制字符串）。
 * <p>
 * MD5 已不适合用于口令存储或抗碰撞安全场景；本类仅用于校验、签名辅助、历史兼容等非强安全用途。
 */
@Slf4j
public final class Md5Util {

	private static final HexFormat HEX = HexFormat.of();

	private Md5Util() {
	}

	/**
	 * 将字节数组转为小写十六进制字符串。
	 *
	 * @param b 字节数组；{@code null} 时返回 {@code null}
	 */
	public static String byteArrayToHexString(byte[] b) {
		if (b == null) {
			return null;
		}
		return HEX.formatHex(b);
	}

	/**
	 * 对字符串做 MD5 摘要并返回十六进制字符串。
	 *
	 * @param origin      原文；{@code null} 时返回 {@code null}
	 * @param charsetname 字符集名称；{@code null} 或空串时使用 {@link StandardCharsets#UTF_8}
	 * @return 32 位小写十六进制；摘要失败时返回 {@code null}
	 */
	public static String md5Encode(String origin, String charsetname) {
		if (origin == null) {
			return null;
		}
		try {
			MessageDigest md = MessageDigest.getInstance("MD5");
			byte[] data;
			if (charsetname == null || charsetname.isEmpty()) {
				data = origin.getBytes(StandardCharsets.UTF_8);
			} else {
				data = origin.getBytes(Charset.forName(charsetname));
			}
			return byteArrayToHexString(md.digest(data));
		} catch (NoSuchAlgorithmException e) {
			log.error("MD5 algorithm not available", e);
			return null;
		} catch (Exception e) {
			log.warn("MD5 digest failed: {}", e.toString());
			return null;
		}
	}

}
