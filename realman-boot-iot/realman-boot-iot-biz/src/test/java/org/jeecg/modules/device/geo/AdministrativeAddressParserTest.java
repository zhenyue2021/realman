package org.jeecg.modules.device.geo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AdministrativeAddressParserTest {

    @Test
    void provinceAndCity() {
        AdministrativeAddressParser.ProvinceCity pc =
                AdministrativeAddressParser.parse("江苏省常州市天宁区");
        assertEquals("江苏省", pc.province());
        assertEquals("常州市", pc.city());
    }

    @Test
    void municipalityDuplicateCityName() {
        AdministrativeAddressParser.ProvinceCity pc =
                AdministrativeAddressParser.parse("北京市北京市东城区");
        assertEquals("北京市", pc.province());
        assertEquals("北京市", pc.city());
    }

    @Test
    void provinceOnly() {
        AdministrativeAddressParser.ProvinceCity pc =
                AdministrativeAddressParser.parse("广东省");
        assertEquals("广东省", pc.province());
        assertEquals(null, pc.city());
    }

    @Test
    void blankAddressThrows() {
        assertThrows(IllegalArgumentException.class, () -> AdministrativeAddressParser.parse(" "));
    }
}
