package org.jeecg.modules.device.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AdministrativeAddressFormatterTest {

    @Test
    void municipalityDuplicatesCityName() {
        assertEquals(
                "北京市北京市门头沟区",
                AdministrativeAddressFormatter.formatProvinceCityDistrict("北京市", null, "门头沟区"));
    }

    @Test
    void provinceAndCity() {
        assertEquals(
                "广东省深圳市南山区",
                AdministrativeAddressFormatter.formatProvinceCityDistrict("广东省", "深圳市", "南山区"));
    }

    @Test
    void provinceOnlyNoDuplicate() {
        assertEquals("广东省", AdministrativeAddressFormatter.formatProvinceCityDistrict("广东省", null, null));
    }

    @Test
    void cityOnlyWithDistrict() {
        assertEquals("深圳市南山区", AdministrativeAddressFormatter.formatProvinceCityDistrict(null, "深圳市", "南山区"));
    }
}
