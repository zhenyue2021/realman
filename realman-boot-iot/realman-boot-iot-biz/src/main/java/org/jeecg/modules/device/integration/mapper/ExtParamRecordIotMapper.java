package org.jeecg.modules.device.integration.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

/**
 * IoT 侧对 integration_external_param_record 表的只读访问。
 *
 * <p>该表由 realman-module-system 写入，IoT 模块仅在 Redis 缓存未命中时
 * 作为降级手段从数据库查询最新一条记录，不做任何写操作。
 */
@Mapper
public interface ExtParamRecordIotMapper {

    /**
     * 按 sourceSystem 查询最新一条记录的 data 字段集合。
     * 返回 Map key 与 Redis 缓存中存储的 JSON key 保持一致，
     * 便于直接组装 {@link org.jeecg.modules.device.mqtt.MqttMessageModel.ExtParamsResponse}。
     */
    @Select("SELECT endpoint, bucket, bj_expiration AS bjExpiration, utc_expiration AS utcExpiration, " +
            "access_key_id AS accessKeyId, access_key_secret AS accessKeySecret, security_token AS securityToken " +
            "FROM integration_external_param_record " +
            "WHERE source_system = #{sourceSystem} " +
            "ORDER BY create_time DESC LIMIT 1")
    Map<String, Object> findLatestDataBySourceSystem(@Param("sourceSystem") String sourceSystem);
}
