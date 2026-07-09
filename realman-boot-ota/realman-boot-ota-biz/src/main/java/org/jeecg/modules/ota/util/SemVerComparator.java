package org.jeecg.modules.ota.util;

import org.jeecg.common.exception.JeecgBootBizTipException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 版本号比较，对齐 OTA 平台详细设计 3.4（PRD 3.3 版本号比较规则）：大写 V 前缀，
 * 三段非负整数，按整数值逐段比较（而非字符串字典序）。
 */
public final class SemVerComparator {

    private static final Pattern PATTERN = Pattern.compile("^V(\\d+)\\.(\\d+)\\.(\\d+)$");

    private SemVerComparator() {
    }

    /** 返回 a-b 的比较结果：负数表示 a&lt;b，0 表示相等，正数表示 a&gt;b。 */
    public static int compare(String a, String b) {
        int[] pa = parse(a);
        int[] pb = parse(b);
        for (int i = 0; i < 3; i++) {
            int diff = pa[i] - pb[i];
            if (diff != 0) {
                return diff;
            }
        }
        return 0;
    }

    public static boolean isValid(String version) {
        return version != null && PATTERN.matcher(version).matches();
    }

    private static int[] parse(String version) {
        Matcher matcher = PATTERN.matcher(version == null ? "" : version);
        if (!matcher.matches()) {
            throw new JeecgBootBizTipException("ERR_INVALID_VERSION_FORMAT: " + version);
        }
        return new int[]{
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        };
    }
}
