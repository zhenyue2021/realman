package org.jeecg.modules.device.datacollect.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jeecg.modules.device.datacollect.constant.DataCollectConstant;
import org.jeecg.modules.device.datacollect.dto.mq.OssAuthResponseMsg;
import org.jeecg.modules.device.datacollect.dto.mqtt.CollectUrlResponseCmd;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.lang.reflect.Field;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OssCredentialCacheServiceTest {

    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOps;
    private OssCredentialCacheService service;

    @BeforeEach
    void setUp() throws Exception {
        redisTemplate = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        service = new OssCredentialCacheService(redisTemplate, new ObjectMapper());
        setField("bufferSeconds", 300L);
        setField("defaultTtlSeconds", 3600L);
        setField("inflightTimeoutSeconds", 60L);
    }

    @Test
    @DisplayName("getIfValid：缓存存在且未临近过期时返回凭证")
    void getIfValidReturnsCredential() throws Exception {
        String future = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        CollectUrlResponseCmd.StsParams params = CollectUrlResponseCmd.StsParams.builder()
                .endpoint("oss-cn-beijing.aliyuncs.com")
                .accessKeyId("STS.test")
                .utcExpiration(future)
                .build();
        String json = new ObjectMapper().writeValueAsString(params);
        when(valueOps.get(DataCollectConstant.REDIS_OSS_CRED_PREFIX + "DEV001")).thenReturn(json);

        Optional<CollectUrlResponseCmd.StsParams> result = service.getIfValid("DEV001");

        assertTrue(result.isPresent());
        assertEquals("STS.test", result.get().getAccessKeyId());
    }

    @Test
    @DisplayName("getIfValid：临近过期时删除缓存并返回空")
    void getIfValidRejectsNearExpiryCredential() throws Exception {
        String nearExpiry = Instant.now().plus(60, ChronoUnit.SECONDS).toString();
        CollectUrlResponseCmd.StsParams params = CollectUrlResponseCmd.StsParams.builder()
                .endpoint("oss-cn-beijing.aliyuncs.com")
                .accessKeyId("STS.test")
                .utcExpiration(nearExpiry)
                .build();
        String json = new ObjectMapper().writeValueAsString(params);
        when(valueOps.get(DataCollectConstant.REDIS_OSS_CRED_PREFIX + "DEV002")).thenReturn(json);

        Optional<CollectUrlResponseCmd.StsParams> result = service.getIfValid("DEV002");

        assertTrue(result.isEmpty());
        verify(redisTemplate).delete(DataCollectConstant.REDIS_OSS_CRED_PREFIX + "DEV002");
    }

    @Test
    @DisplayName("put：按 utcExpiration 计算 TTL 并写入 Redis")
    void putStoresCredentialWithComputedTtl() {
        String future = Instant.now().plus(2, ChronoUnit.HOURS).toString();
        OssAuthResponseMsg.MsgData data = new OssAuthResponseMsg.MsgData();
        data.setEndpoint("oss-cn-beijing.aliyuncs.com");
        data.setBucket("embodied-data");
        data.setAccessKeyId("STS.test");
        data.setAccessKeySecret("secret");
        data.setSecurityToken("token");
        data.setUtcExpiration(future);

        service.put("DEV003", data);

        ArgumentCaptor<Long> ttlCaptor = ArgumentCaptor.forClass(Long.class);
        verify(valueOps).set(eq(DataCollectConstant.REDIS_OSS_CRED_PREFIX + "DEV003"), any(String.class),
                ttlCaptor.capture(), eq(TimeUnit.SECONDS));
        assertTrue(ttlCaptor.getValue() > 3000L);
    }

    @Test
    @DisplayName("tryAcquireInflight：首次获取成功，重复获取失败")
    void inflightLockBehavior() {
        when(valueOps.setIfAbsent(
                eq(DataCollectConstant.REDIS_OSS_INFLIGHT_PREFIX + "DEV004"),
                eq("req-1"), eq(60L), eq(TimeUnit.SECONDS)))
                .thenReturn(true)
                .thenReturn(false);

        assertTrue(service.tryAcquireInflight("DEV004", "req-1"));
        assertFalse(service.tryAcquireInflight("DEV004", "req-2"));
    }

    @Test
    @DisplayName("put：失败响应不写入缓存")
    void putSkipsFailedResponse() {
        OssAuthResponseMsg.MsgData data = new OssAuthResponseMsg.MsgData();
        data.setErrorCode("ERR");
        data.setErrorMsg("failed");

        service.put("DEV005", data);

        verify(valueOps, never()).set(any(), any(), any(Long.class), any(TimeUnit.class));
    }

    private void setField(String name, long value) throws Exception {
        Field field = OssCredentialCacheService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
