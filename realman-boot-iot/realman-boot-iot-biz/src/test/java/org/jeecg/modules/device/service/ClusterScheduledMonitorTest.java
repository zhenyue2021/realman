package org.jeecg.modules.device.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.verify;

class ClusterScheduledMonitorTest {

    private static final String STOP_PREFIX = "iot:test:monitor:stop:";

    private StringRedisTemplate redisTemplate;
    private TestMonitor monitor;

    @BeforeEach
    void setUp() {
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        monitor = new TestMonitor(redisTemplate, scheduler);
    }

    @Test
    @DisplayName("broadcastStop 发布 Redis 停止频道")
    void broadcastStopPublishesMessage() {
        monitor.broadcastStop("ROBOT1");
        verify(redisTemplate).convertAndSend(STOP_PREFIX + "ROBOT1", "stop");
    }

    @Test
    @DisplayName("onMessage 取消本节点定时任务")
    void onMessageCancelsLocalTask() throws Exception {
        monitor.scheduleFixedDelay("ROBOT1", () -> {
        }, 1, 60, TimeUnit.SECONDS);

        Message message = new DefaultMessage(
                (STOP_PREFIX + "ROBOT1").getBytes(StandardCharsets.UTF_8),
                "stop".getBytes(StandardCharsets.UTF_8));
        monitor.onMessage(message, STOP_PREFIX.getBytes(StandardCharsets.UTF_8));

        assert !monitor.hasLocalTask("ROBOT1");
    }

    private static final class TestMonitor extends ClusterScheduledMonitor {
        TestMonitor(StringRedisTemplate redisTemplate, ScheduledExecutorService scheduler) {
            super(redisTemplate, STOP_PREFIX, "TestMonitor", scheduler);
        }
    }
}
