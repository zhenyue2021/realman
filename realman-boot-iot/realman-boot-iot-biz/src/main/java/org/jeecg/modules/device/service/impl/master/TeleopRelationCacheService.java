package org.jeecg.modules.device.service.impl.master;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 主控 ↔ 机器人遥操关系 Redis 缓存（双向索引，TTL 24h）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TeleopRelationCacheService {

    private static final long TTL_HOURS = 24L;

    private final StringRedisTemplate redisTemplate;

    /** 绑定主控与机器人双向关系，切换机器人时会清理旧反向索引。 */
    public void bind(String masterCode, String robotCode) {
        if (isBlank(masterCode) || isBlank(robotCode)) {
            return;
        }
        String masterKey = DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterCode;
        String prevRobotCode = redisTemplate.opsForValue().get(masterKey);
        if (prevRobotCode != null && !prevRobotCode.equals(robotCode)) {
            clearReverseIfPointsToMaster(prevRobotCode, masterCode);
            log.info("[TeleopCache] 清理旧机器人反向缓存: oldRobot={} master={}", prevRobotCode, masterCode);
        }
        redisTemplate.opsForValue().set(masterKey, robotCode, TTL_HOURS, TimeUnit.HOURS);
        redisTemplate.opsForValue().set(
                DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode, masterCode,
                TTL_HOURS, TimeUnit.HOURS);
        log.info("[TeleopCache] 写入遥操关系缓存: master={} robot={}", masterCode, robotCode);
    }

    /** 主控离线或结束遥操时清理。 */
    public void clearByMaster(String masterCode) {
        if (isBlank(masterCode)) {
            return;
        }
        String masterKey = DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterCode;
        String robotCode = redisTemplate.opsForValue().get(masterKey);
        redisTemplate.delete(masterKey);
        if (!isBlank(robotCode)) {
            clearReverseIfPointsToMaster(robotCode, masterCode);
        }
        log.info("[TeleopCache] 清除主控遥操缓存: master={} robot={}", masterCode, robotCode);
    }

    /** 按机器人编码查询当前绑定的主控编码，未绑定返回 null。 */
    public String getMasterByRobot(String robotCode) {
        if (isBlank(robotCode)) {
            return null;
        }
        return redisTemplate.opsForValue().get(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode);
    }

    /** 机器人离线或结束遥操时清理。 */
    public void clearByRobot(String robotCode) {
        if (isBlank(robotCode)) {
            return;
        }
        String robotKey = DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode;
        String masterCode = redisTemplate.opsForValue().get(robotKey);
        redisTemplate.delete(robotKey);
        if (!isBlank(masterCode)) {
            clearForwardIfPointsToRobot(masterCode, robotCode);
        }
        log.info("[TeleopCache] 清除机器人遥操缓存: robot={} master={}", robotCode, masterCode);
    }

    private void clearReverseIfPointsToMaster(String robotCode, String masterCode) {
        String robotKey = DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + robotCode;
        String boundMaster = redisTemplate.opsForValue().get(robotKey);
        if (masterCode.equals(boundMaster)) {
            redisTemplate.delete(robotKey);
        }
    }

    private void clearForwardIfPointsToRobot(String masterCode, String robotCode) {
        String masterKey = DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + masterCode;
        String boundRobot = redisTemplate.opsForValue().get(masterKey);
        if (robotCode.equals(boundRobot)) {
            redisTemplate.delete(masterKey);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
