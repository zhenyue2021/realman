package org.jeecg.modules.ota.util;

import org.jeecg.common.exception.JeecgBootBizTipException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 版本号比较是 OTA 版本矩阵/预检的核心依据，覆盖 PRD 3.3 规定的按整数值逐段比较
 * （而非字符串字典序）这一最容易被误实现的点。
 */
class SemVerComparatorTest {

    @Test
    void compare_shouldUseNumericNotLexicographicOrdering() {
        // 字符串字典序会把 "V1.10.0" 判定为小于 "V1.9.0"（'1' < '9'），PRD 3.3 明确要求按数值比较
        assertThat(SemVerComparator.compare("V1.10.0", "V1.9.0")).isGreaterThan(0);
        assertThat(SemVerComparator.compare("V1.9.0", "V1.10.0")).isLessThan(0);
    }

    @Test
    void compare_shouldReturnZeroForEqualVersions() {
        assertThat(SemVerComparator.compare("V2.0.3", "V2.0.3")).isZero();
    }

    @Test
    void compare_shouldCompareMajorThenMinorThenPatch() {
        assertThat(SemVerComparator.compare("V2.0.0", "V1.99.99")).isGreaterThan(0);
        assertThat(SemVerComparator.compare("V1.2.0", "V1.1.99")).isGreaterThan(0);
        assertThat(SemVerComparator.compare("V1.2.3", "V1.2.4")).isLessThan(0);
    }

    @Test
    void isValid_shouldRequireUppercaseVAndThreeNonNegativeIntegerSegments() {
        assertThat(SemVerComparator.isValid("V1.0.0")).isTrue();
        assertThat(SemVerComparator.isValid("v1.0.0")).isFalse();
        assertThat(SemVerComparator.isValid("V1.0")).isFalse();
        assertThat(SemVerComparator.isValid("V1.0.0.1")).isFalse();
        assertThat(SemVerComparator.isValid("V1.-1.0")).isFalse();
        assertThat(SemVerComparator.isValid(null)).isFalse();
    }

    @Test
    void compare_shouldThrowOnInvalidFormat() {
        assertThatThrownBy(() -> SemVerComparator.compare("1.0.0", "V1.0.0"))
                .isInstanceOf(JeecgBootBizTipException.class)
                .hasMessageContaining("ERR_INVALID_VERSION_FORMAT");
    }
}
