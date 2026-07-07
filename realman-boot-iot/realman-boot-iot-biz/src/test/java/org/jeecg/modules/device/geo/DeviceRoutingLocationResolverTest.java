package org.jeecg.modules.device.geo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceRoutingLocationResolverTest {

    @Mock
    private DeviceIpGeoResolver deviceIpGeoResolver;

    private DeviceRoutingLocationResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DeviceRoutingLocationResolver(deviceIpGeoResolver);
    }

    @Test
    @DisplayName("address 已是行政区划文案时直接解析")
    void resolveFromAdministrativeText() {
        AdministrativeAddressParser.ProvinceCity loc =
                resolver.resolve("江苏省常州市天宁区");

        assertThat(loc.province()).isEqualTo("江苏省");
        assertThat(loc.city()).isEqualTo("常州市");
    }

    @Test
    @DisplayName("address 为纯 IP 时经 IpGeoResolver 解析后再提取省市")
    void resolveFromStoredIpUsesGeoResolver() {
        when(deviceIpGeoResolver.resolveAdministrativeAddress("58.216.151.106"))
                .thenReturn("江苏省常州市天宁区");

        AdministrativeAddressParser.ProvinceCity loc =
                resolver.resolve("58.216.151.106");

        assertThat(loc.province()).isEqualTo("江苏省");
        assertThat(loc.city()).isEqualTo("常州市");
        verify(deviceIpGeoResolver).resolveAdministrativeAddress("58.216.151.106");
    }

    @Test
    @DisplayName("IpGeo 未命中时回退原 address 并报解析失败")
    void resolveFailsWhenIpGeoMisses() {
        when(deviceIpGeoResolver.resolveAdministrativeAddress("58.216.151.106"))
                .thenReturn(null);

        assertThatThrownBy(() -> resolver.resolve("58.216.151.106"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法解析设备地理位置");
    }
}
