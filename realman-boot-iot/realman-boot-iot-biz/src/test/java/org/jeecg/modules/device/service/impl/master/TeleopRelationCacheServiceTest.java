package org.jeecg.modules.device.service.impl.master;

import org.jeecg.modules.device.constant.DeviceConstant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeleopRelationCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private TeleopRelationCacheService service;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new TeleopRelationCacheService(redisTemplate);
    }

    @Test
    @DisplayName("bind 写入双向遥操关系")
    void bindWritesBothDirections() {
        when(valueOps.get(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + "M1")).thenReturn(null);

        service.bind("M1", "R1");

        verify(valueOps).set(
                eq(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + "M1"),
                eq("R1"), eq(24L), eq(TimeUnit.HOURS));
        verify(valueOps).set(
                eq(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + "R1"),
                eq("M1"), eq(24L), eq(TimeUnit.HOURS));
    }

    @Test
    @DisplayName("getMasterByRobot 返回 Redis 中的主控编码")
    void getMasterByRobotReturnsCachedMaster() {
        when(valueOps.get(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + "R1")).thenReturn("M1");

        String master = service.getMasterByRobot("R1");

        org.junit.jupiter.api.Assertions.assertEquals("M1", master);
    }

    @Test
    @DisplayName("clearByMaster 删除主控及匹配反向索引")
    void clearByMasterDeletesKeys() {
        when(valueOps.get(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + "M1")).thenReturn("R1");
        when(valueOps.get(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + "R1")).thenReturn("M1");

        service.clearByMaster("M1");

        verify(redisTemplate).delete(DeviceConstant.RedisKey.TELEOP_MASTER_TO_ROBOT + "M1");
        verify(redisTemplate).delete(DeviceConstant.RedisKey.TELEOP_ROBOT_TO_MASTER + "R1");
    }
}
