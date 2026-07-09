package org.jeecg.modules.devicemgmt.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** {@code DeviceSecretCacheService} 的 Redis 缓存值结构。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CachedDeviceSecret implements Serializable {

    private static final long serialVersionUID = 1L;

    private String deviceId;

    private String deviceSecretHash;
}
