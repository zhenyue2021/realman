package org.jeecg.modules.integration.service;

import com.baomidou.mybatisplus.extension.service.IService;
import org.jeecg.modules.integration.dto.ExternalParamReceiveDTO;
import org.jeecg.modules.integration.entity.ExternalParamRecord;

import java.util.Map;

public interface IExternalParamRecordService extends IService<ExternalParamRecord> {

    /**
     * 接收外部参数，落库并更新 Redis 缓存。
     *
     * @return true=首次存储成功；false=requestId 重复，已幂等忽略
     */
    boolean receiveAndStore(ExternalParamReceiveDTO dto);

    /**
     * 从 Redis 缓存查询指定来源系统+目标系统的最新 data 参数。
     * 缓存未命中时返回 null。
     *
     * @param sourceSystem 外部系统编码，如 "DEW"
     * @param targetSystem 目标系统编码
     */
    Map<String, Object> getCachedData(String sourceSystem, String targetSystem);
}
