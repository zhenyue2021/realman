package org.jeecg.modules.device.datacollect.log;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.entity.IotMqMessageLog;
import org.jeecg.modules.device.mapper.IotMqMessageLogMapper;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.concurrent.Executor;

@Slf4j
@Service
public class MqMessageLogService extends ServiceImpl<IotMqMessageLogMapper, IotMqMessageLog> {

    private static final int MAX_BODY_LEN = 4000;

    private final Executor devicePersistExecutor;

    public MqMessageLogService(@Qualifier("devicePersistExecutor") Executor devicePersistExecutor) {
        this.devicePersistExecutor = devicePersistExecutor;
    }

    /**
     * 异步写入 MQ 收发日志，不阻塞调用方线程。
     * traceId 优先取 record 上已有的值，次之从当前线程 MDC 获取。
     * devicePersistExecutor 的 taskDecorator 会自动传播 MDC，但 MDC
     * 在某些场景（如 consumer 的 finally 清理后）可能已清空，因此提前捕获。
     */
    public void asyncSave(IotMqMessageLog record) {
        if (record.getTraceId() == null) {
            record.setTraceId(MDC.get("traceId"));
        }
        devicePersistExecutor.execute(() -> {
            try {
                if (record.getCreateTime() == null) {
                    record.setCreateTime(new Date());
                }
                if (record.getMessageBody() != null && record.getMessageBody().length() > MAX_BODY_LEN) {
                    record.setMessageBody(record.getMessageBody().substring(0, MAX_BODY_LEN) + "...[truncated]");
                }
                save(record);
            } catch (Exception e) {
                log.warn("[MqLog] 日志写入失败 direction={} topic={} tag={}",
                        record.getDirection(), record.getTopic(), record.getTag(), e);
            }
        });
    }
}
