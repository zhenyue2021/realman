package org.jeecg.modules.device.geo;

import java.util.List;

/**
 * 从 {@code iot_device.address} 行政区划文案反向解析省、市，供 turn_router 调度使用。
 *
 * <p>示例：
 * <ul>
 *   <li>{@code 江苏省常州市天宁区} → province=江苏省, city=常州市</li>
 *   <li>{@code 北京市北京市东城区} → province=北京市, city=北京市</li>
 * </ul>
 */
public final class AdministrativeAddressParser {

    private static final List<String> MUNICIPALITIES = List.of(
            "北京市", "上海市", "天津市", "重庆市");

    private AdministrativeAddressParser() {
    }

    public record ProvinceCity(String province, String city) {
    }

    /**
     * @throws IllegalArgumentException address 为空或无法解析 province 时
     */
    public static ProvinceCity parse(String address) {
        if (address == null || address.isBlank()) {
            throw new IllegalArgumentException("设备地理位置未上报");
        }
        String text = address.trim();

        for (String municipality : MUNICIPALITIES) {
            if (text.startsWith(municipality)) {
                return new ProvinceCity(municipality, municipality);
            }
        }

        int provinceEnd = text.indexOf('省');
        if (provinceEnd > 0) {
            String province = text.substring(0, provinceEnd + 1);
            String rest = text.substring(provinceEnd + 1);
            int cityEnd = rest.indexOf('市');
            if (cityEnd > 0) {
                return new ProvinceCity(province, rest.substring(0, cityEnd + 1));
            }
            return new ProvinceCity(province, null);
        }

        int autonomousEnd = text.indexOf("自治区");
        if (autonomousEnd > 0) {
            String province = text.substring(0, autonomousEnd + 3);
            String rest = text.substring(autonomousEnd + 3);
            int cityEnd = rest.indexOf('市');
            if (cityEnd > 0) {
                return new ProvinceCity(province, rest.substring(0, cityEnd + 1));
            }
            return new ProvinceCity(province, null);
        }

        int cityEnd = text.indexOf('市');
        if (cityEnd > 0) {
            String city = text.substring(0, cityEnd + 1);
            return new ProvinceCity(city, city);
        }

        throw new IllegalArgumentException("无法解析设备地理位置: " + address);
    }
}
