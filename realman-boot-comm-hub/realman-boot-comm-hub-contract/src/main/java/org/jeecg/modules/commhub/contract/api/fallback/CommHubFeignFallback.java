package org.jeecg.modules.commhub.contract.api.fallback;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.api.vo.Result;
import org.jeecg.modules.commhub.contract.api.CommHubFeignClient;
import org.jeecg.modules.commhub.contract.dto.MqttPublishRequest;
import org.jeecg.modules.commhub.contract.dto.MqttPublishResult;

@Slf4j
public class CommHubFeignFallback implements CommHubFeignClient {

    @Setter
    private Throwable cause;

    @Override
    public Result<MqttPublishResult> publish(MqttPublishRequest request) {
        log.error("[comm-hub] publish 调用失败，服务不可用 deviceId={} topicSuffix={}",
                request.getDeviceId(), request.getTopicSuffix(), cause);
        return Result.error("设备通信中台暂不可用，指令未下发");
    }
}
