package org.jeecg.modules.commhub.contract.api.fallback;

import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

@Component
public class CommHubFeignFallbackFactory implements FallbackFactory<CommHubFeignClient> {

    @Override
    public CommHubFeignClient create(Throwable cause) {
        CommHubFeignFallback fallback = new CommHubFeignFallback();
        fallback.setCause(cause);
        return fallback;
    }
}
