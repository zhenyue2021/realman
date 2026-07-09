package org.jeecg.modules.ota.service;

import java.util.Map;

/**
 * 系统设置读写 + 交叉校验入口，对应 {@code POST /api/v1/system/config/validate}
 * （PRD 9.9）。所有配置修改（UI/API）都必须过 {@link #validateAndApply}。
 */
public interface IOtaSystemSettingService {

    Map<String, String> getAll();

    String getString(String key);

    int getInt(String key);

    long getLong(String key);

    boolean getBoolean(String key);

    /** 仅校验，不落库，供前端预检/其他服务复用同一套规则。 */
    void validate(Map<String, String> changes);

    /** 校验通过后落库并写审计。 */
    void validateAndApply(Map<String, String> changes, String operator);
}
