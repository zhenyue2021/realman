package org.jeecg.modules.deviceinfo.contract.api.fallback;

import org.jeecg.modules.deviceinfo.contract.api.DeviceInfoFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class DeviceInfoFeignFallbackFactory implements FallbackFactory<DeviceInfoFeignClient> {

    @Override
    public DeviceInfoFeignClient create(Throwable cause) {
        DeviceInfoFeignFallback fallback = new DeviceInfoFeignFallback();
        fallback.setCause(cause);
        return fallback;
    }
}
