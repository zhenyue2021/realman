package org.jeecg.modules.monitor.service.impl;

import java.util.*;
import java.util.concurrent.TimeUnit;

import jakarta.annotation.Resource;

import cn.hutool.core.date.DateUtil;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.collect.Maps;
import org.jeecg.common.util.oConvertUtils;
import org.jeecg.modules.monitor.domain.RedisInfo;
import org.jeecg.modules.monitor.exception.RedisConnectException;
import org.jeecg.modules.monitor.service.RedisService;
import org.springframework.cglib.beans.BeanMap;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 监控信息获取
 *
 * @Author MrBird
 */
@Service("redisService")
@Slf4j
public class RedisServiceImpl implements RedisService {

	@Resource
	private RedisConnectionFactory redisConnectionFactory;

	@Resource
	private StringRedisTemplate stringRedisTemplate;

	/** 分布式锁：防止多节点重复采集，TTL 略小于采集间隔（60s）以防锁泄漏 */
	private static final String METRICS_LOCK_KEY   = "monitor:redis:metrics:lock";
	private static final long   METRICS_LOCK_TTL   = 55L;

	/** Redis ZSET：存储历史采集数据，score = 采集时间戳（毫秒） */
	private static final String METRICS_KEY_DBSIZE = "monitor:redis:metrics:dbSize";
	private static final String METRICS_KEY_MEMORY = "monitor:redis:metrics:memory";
	/** 最多保留最近 60 条（对应 1 小时采集数据） */
	private static final int    METRICS_MAX_SIZE   = 60;
	/** ZSET 键 TTL：2 小时，超过后自动清理 */
	private static final long   METRICS_DATA_TTL   = 7200L;

    /**
     * redis信息
     */
    private static final String REDIS_MESSAGE = "3";

	/**
	 * Redis详细信息
	 */
	@Override
	public List<RedisInfo> getRedisInfo() throws RedisConnectException {
		Properties info = redisConnectionFactory.getConnection().info();
		List<RedisInfo> infoList = new ArrayList<>();
		RedisInfo redisInfo = null;
		for (Map.Entry<Object, Object> entry : info.entrySet()) {
			redisInfo = new RedisInfo();
			redisInfo.setKey(oConvertUtils.getString(entry.getKey()));
			redisInfo.setValue(oConvertUtils.getString(entry.getValue()));
			infoList.add(redisInfo);
		}
		return infoList;
	}

	@Override
	public Map<String, Object> getKeysSize() throws RedisConnectException {
		Long dbSize = redisConnectionFactory.getConnection().dbSize();
		Map<String, Object> map = new HashMap(5);
		map.put("create_time", System.currentTimeMillis());
		map.put("dbSize", dbSize);

		log.debug("--getKeysSize--: " + map.toString());
		return map;
	}

	@Override
	public Map<String, Object> getMemoryInfo() throws RedisConnectException {
		Map<String, Object> map = null;
		Properties info = redisConnectionFactory.getConnection().info();
		for (Map.Entry<Object, Object> entry : info.entrySet()) {
			String key = oConvertUtils.getString(entry.getKey());
			if ("used_memory".equals(key)) {
				map = new HashMap(5);
				map.put("used_memory", entry.getValue());
				map.put("create_time", System.currentTimeMillis());
			}
		}
		log.debug("--getMemoryInfo--: " + map.toString());
		return map;
	}

    /**
     * 查询redis信息for报表
     * @param type 1redis key数量 2 占用内存 3redis信息
     * @return
     * @throws RedisConnectException
     */
	@Override
	public Map<String, JSONArray> getMapForReport(String type)  throws RedisConnectException {
		Map<String,JSONArray> mapJson=new HashMap(5);
		JSONArray json = new JSONArray();
		if(REDIS_MESSAGE.equals(type)){
			List<RedisInfo> redisInfo = getRedisInfo();
			for(RedisInfo info:redisInfo){
				Map<String, Object> map= Maps.newHashMap();
				BeanMap beanMap = BeanMap.create(info);
				for (Object key : beanMap.keySet()) {
					map.put(key+"", beanMap.get(key));
				}
				json.add(map);
			}
			mapJson.put("data",json);
			return mapJson;
		}
		int length = 5;
		for(int i = 0; i < length; i++){
			JSONObject jo = new JSONObject();
			Map<String, Object> map;
			if("1".equals(type)){
				map= getKeysSize();
				jo.put("value",map.get("dbSize"));
			}else{
				map = getMemoryInfo();
				Integer usedMemory = Integer.valueOf(map.get("used_memory").toString());
				jo.put("value",usedMemory/1000);
			}
			String createTime = DateUtil.formatTime(DateUtil.date((Long) map.get("create_time")-(4-i)*1000));
			jo.put("name",createTime);
			json.add(jo);
		}
		mapJson.put("data",json);
		return mapJson;
	}

	/**
	 * 获取历史性能指标（从 Redis ZSET 读取，集群所有节点返回一致数据）
	 * @return key: dbSize/memory, value: 按时间升序排列的采集记录列表
	 * @author chenrui
	 * @date 2024/5/14 14:57
	 */
	@Override
	public Map<String, List<Map<String, Object>>> getMetricsHistory() {
		Map<String, List<Map<String, Object>>> result = new HashMap<>(2);
		result.put("dbSize", readMetricsFromZSet(METRICS_KEY_DBSIZE));
		result.put("memory", readMetricsFromZSet(METRICS_KEY_MEMORY));
		return result;
	}

	/**
	 * 从 Redis ZSET 按 score（时间戳）升序读取所有采集记录
	 */
	private List<Map<String, Object>> readMetricsFromZSet(String key) {
		Set<String> members = stringRedisTemplate.opsForZSet().range(key, 0, -1);
		if (members == null || members.isEmpty()) {
			return new ArrayList<>();
		}
		List<Map<String, Object>> list = new ArrayList<>(members.size());
		for (String json : members) {
			try {
				list.add(JSONObject.parseObject(json, Map.class));
			} catch (Exception e) {
				log.warn("[Redis监控] 解析历史记录失败，key={}, json={}", key, json, e);
			}
		}
		return list;
	}

	/**
	 * 记录近一小时 Redis 监控数据（写入 Redis ZSET，集群安全） <br/>
	 * 60s 一次，分布式锁保证多节点只有一个执行，数据存 Redis 供所有节点读取
	 * @throws RedisConnectException
	 * @author chenrui
	 * @date 2024/5/14 14:09
	 */
	@Scheduled(fixedRate = 60000)
	public void recordCustomMetric() throws RedisConnectException {
		Boolean locked = stringRedisTemplate.opsForValue()
				.setIfAbsent(METRICS_LOCK_KEY, "1", METRICS_LOCK_TTL, TimeUnit.SECONDS);
		if (!Boolean.TRUE.equals(locked)) {
			log.debug("[Redis监控] 其他节点已持有采集锁，跳过本次采集");
			return;
		}

		long score = System.currentTimeMillis();

		// 采集 Key 数量，写入 ZSET
		Map<String, Object> keySizeData = getKeysSize();
		String keySizeJson = JSONObject.toJSONString(keySizeData);
		writeMetricsToZSet(METRICS_KEY_DBSIZE, keySizeJson, score);

		// 采集内存占用，写入 ZSET
		Map<String, Object> memoryData = getMemoryInfo();
		String memoryJson = JSONObject.toJSONString(memoryData);
		writeMetricsToZSet(METRICS_KEY_MEMORY, memoryJson, score);
	}

	/**
	 * 向 Redis ZSET 写入一条采集记录，并裁剪超出上限的旧数据、刷新 TTL
	 */
	private void writeMetricsToZSet(String key, String json, long score) {
		stringRedisTemplate.opsForZSet().add(key, json, score);
		// 删除最早的数据，保留最新 METRICS_MAX_SIZE 条
		long total = Optional.ofNullable(stringRedisTemplate.opsForZSet().zCard(key)).orElse(0L);
		if (total > METRICS_MAX_SIZE) {
			stringRedisTemplate.opsForZSet().removeRange(key, 0, total - METRICS_MAX_SIZE - 1);
		}
		// 刷新 TTL，防止键永久驻留
		stringRedisTemplate.expire(key, METRICS_DATA_TTL, TimeUnit.SECONDS);
	}
}
