package org.jeecg.modules.ota.contract.api.fallback;

import org.jeecg.modules.ota.contract.api.OtaFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class OtaFeignFallbackFactory implements FallbackFactory<OtaFeignClient> {

    @Override
    public OtaFeignClient create(Throwable cause) {
        OtaFeignFallback fallback = new OtaFeignFallback();
        fallback.setCause(cause);
        return fallback;
    }
}
