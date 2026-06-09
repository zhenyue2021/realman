package org.jeecg.modules.integration.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.integration.dto.ExternalParamReceiveDTO;
import org.jeecg.modules.integration.entity.ExternalParamRecord;
import org.jeecg.modules.integration.mapper.ExternalParamRecordMapper;
import org.jeecg.modules.integration.service.IExternalParamRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ExternalParamRecordServiceImpl extends ServiceImpl<ExternalParamRecordMapper, ExternalParamRecord>
        implements IExternalParamRecordService {

    /** Redis key 前缀：ext:param:{sourceSystem}:{targetSystem} */
    private static final String REDIS_KEY_PREFIX = "realman:ext:param:";

    /** utcExpiration 解析失败时的兜底 TTL（1 小时） */
    private static final long DEFAULT_TTL_SECONDS = 3600L;

    private final RedisUtil redisUtil;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean receiveAndStore(ExternalParamReceiveDTO dto) {
        // 1. 幂等校验：同一 requestId 只处理一次
        long existing = this.count(new LambdaQueryWrapper<ExternalParamRecord>()
                .eq(ExternalParamRecord::getRequestId, dto.getRequestId()));
        if (existing > 0) {
            log.info("[ExternalParam] 重复 requestId，幂等忽略: requestId={}", dto.getRequestId());
            return false;
        }

        // 2. 解析 params.timestamp 与 params.data
        Map<String, Object> params = dto.getParams();
        String paramTimestamp = (String) params.get("timestamp");
        Object dataObj = params.get("data");
        JSONObject data = dataObj instanceof Map
                ? new JSONObject((Map<String, Object>) dataObj)
                : null;

        // 3. 构建持久化实体
        ExternalParamRecord record = new ExternalParamRecord();
        record.setSourceSystem(dto.getSourceSystem());
        record.setTargetSystem(dto.getTargetSystem());
        record.setRequestId(dto.getRequestId());
        record.setBizType(dto.getBizType());
        record.setParamTimestamp(paramTimestamp);
        record.setRawParams(JSON.toJSONString(params));

        if (data != null) {
            record.setEndpoint(data.getString("endpoint"));
            record.setBucket(data.getString("bucket"));
            record.setBjExpiration(data.getString("bjExpiration"));
            record.setUtcExpiration(data.getString("utcExpiration"));
            record.setAccessKeyId(data.getString("accessKeyId"));
            record.setAccessKeySecret(data.getString("accessKeySecret"));
            record.setSecurityToken(data.getString("securityToken"));
        }

        // 4. 落库
        this.save(record);
        log.info("[ExternalParam] 参数已入库: id={}, sourceSystem={}, requestId={}",
                record.getId(), dto.getSourceSystem(), dto.getRequestId());

        // 5. 写入 Redis，key = ext:param:{sourceSystem}:{targetSystem}，value = data JSON
        //    TTL 跟随凭证过期时间（utcExpiration），解析失败则用默认 1 小时
        if (data != null) {
            String cacheKey = REDIS_KEY_PREFIX + dto.getSourceSystem() + ":" + dto.getTargetSystem();
            long ttl = computeTtlSeconds(record.getUtcExpiration());
            redisUtil.set(cacheKey, data.toJSONString());
            redisUtil.expire(cacheKey, ttl);
            log.info("[ExternalParam] 缓存已刷新: key={}, ttl={}s", cacheKey, ttl);
        }

        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> getCachedData(String sourceSystem, String targetSystem) {
        String cacheKey = REDIS_KEY_PREFIX + sourceSystem + ":" + targetSystem;
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return JSON.parseObject(cached.toString(), Map.class);
        }

        // 缓存未命中，降级查库：取该来源系统+目标系统最新一条记录
        log.warn("[ExternalParam] 缓存未命中，降级查库: sourceSystem={}, targetSystem={}", sourceSystem, targetSystem);
        ExternalParamRecord record = this.getOne(
                new LambdaQueryWrapper<ExternalParamRecord>()
                        .eq(ExternalParamRecord::getSourceSystem, sourceSystem)
                        .eq(ExternalParamRecord::getTargetSystem, targetSystem)
                        .orderByDesc(ExternalParamRecord::getCreateTime)
                        .last("LIMIT 1"));
        if (record == null) {
            log.warn("[ExternalParam] 库中亦无数据: sourceSystem={}, targetSystem={}", sourceSystem, targetSystem);
            return null;
        }

        // 从记录中重组 data Map 并回写缓存
        Map<String, Object> data = buildDataMap(record);
        long ttl = computeTtlSeconds(record.getUtcExpiration());
        redisUtil.set(cacheKey, JSON.toJSONString(data));
        redisUtil.expire(cacheKey, ttl);
        log.info("[ExternalParam] 降级查库成功，已回写缓存: sourceSystem={}, targetSystem={}, ttl={}s", sourceSystem, targetSystem, ttl);
        return data;
    }

    /** 将实体中的 data 字段重组为 Map，与缓存中存储的结构保持一致 */
    private Map<String, Object> buildDataMap(ExternalParamRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("endpoint",       record.getEndpoint());
        data.put("bucket",         record.getBucket());
        data.put("bjExpiration",   record.getBjExpiration());
        data.put("utcExpiration",  record.getUtcExpiration());
        data.put("accessKeyId",    record.getAccessKeyId());
        data.put("accessKeySecret", record.getAccessKeySecret());
        data.put("securityToken",  record.getSecurityToken());
        return data;
    }

    /**
     * 根据 UTC 过期时间字符串（ISO-8601，如 "2026-02-25T09:28:15Z"）计算剩余秒数。
     * 解析失败或已过期时返回默认 TTL。
     */
    private long computeTtlSeconds(String utcExpiration) {
        if (utcExpiration == null || utcExpiration.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            long remaining = Instant.parse(utcExpiration).getEpochSecond() - Instant.now().getEpochSecond();
            return remaining > 0 ? remaining : DEFAULT_TTL_SECONDS;
        } catch (Exception e) {
            log.warn("[ExternalParam] utcExpiration 解析失败，使用默认 TTL: value={}", utcExpiration);
            return DEFAULT_TTL_SECONDS;
        }
    }
}
