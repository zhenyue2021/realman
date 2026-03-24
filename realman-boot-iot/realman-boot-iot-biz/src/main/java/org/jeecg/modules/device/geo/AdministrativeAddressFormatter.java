package org.jeecg.modules.device.geo;

/**
 * 将省 / 市 / 区县级字段格式化为与高德展示习惯一致的字符串，
 * 例如直辖市「北京市北京市门头沟区」、普通省「广东省深圳市南山区」。
 */
public final class AdministrativeAddressFormatter {

    private AdministrativeAddressFormatter() {
    }

    public static String formatProvinceCityDistrict(String province, String city, String district) {
        if (isEmpty(province) && isEmpty(city)) {
            return null;
        }
        String dist = district == null ? "" : district.trim();

        if (isEmpty(province) && !isEmpty(city)) {
            StringBuilder sb = new StringBuilder(city.trim());
            if (!dist.isEmpty()) {
                sb.append(dist);
            }
            return sb.toString();
        }
        if (isEmpty(province)) {
            return null;
        }

        String prov = province.trim();
        // 直辖市：city 未给出时重复市名；普通省：无市名时不重复省名
        String cit;
        if (isEmpty(city)) {
            cit = isMunicipalityProvince(prov) ? prov : "";
        } else {
            cit = city.trim();
        }

        StringBuilder sb = new StringBuilder();
        sb.append(prov);
        if (!cit.isEmpty()) {
            sb.append(cit);
        }
        if (!dist.isEmpty()) {
            sb.append(dist);
        }
        return sb.toString();
    }

    private static boolean isMunicipalityProvince(String prov) {
        return "北京市".equals(prov)
                || "上海市".equals(prov)
                || "天津市".equals(prov)
                || "重庆市".equals(prov);
    }

    private static boolean isEmpty(String s) {
        return s == null || s.isBlank();
    }
}
