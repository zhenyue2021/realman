package org.jeecg.modules.devicemgmt.contract.api.fallback;

import org.jeecg.modules.devicemgmt.contract.api.DeviceMgmtFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class DeviceMgmtFeignFallbackFactory implements FallbackFactory<DeviceMgmtFeignClient> {

    @Override
    public DeviceMgmtFeignClient create(Throwable cause) {
        DeviceMgmtFeignFallback fallback = new DeviceMgmtFeignFallback();
        fallback.setCause(cause);
        return fallback;
    }
}
