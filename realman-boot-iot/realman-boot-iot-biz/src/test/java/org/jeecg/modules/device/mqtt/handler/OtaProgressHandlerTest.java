package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeRecordMapper;
import org.jeecg.modules.device.mapper.IotOtaUpgradeTaskMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 使用真实 AES 加解密逻辑的 OTA 进度上报处理测试。
 */
public class OtaProgressHandlerTest {

    private IotDeviceMapper deviceMapper;
    private IotOtaUpgradeRecordMapper recordMapper;
    private IotOtaUpgradeTaskMapper taskMapper;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private IDeviceOperationLogService logService;
    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;

    private OtaProgressHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDevice.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotOtaUpgradeRecord.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        recordMapper = Mockito.mock(IotOtaUpgradeRecordMapper.class);
        taskMapper = Mockito.mock(IotOtaUpgradeTaskMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        logService = Mockito.mock(IDeviceOperationLogService.class);
        objectMapper = new ObjectMapper();

        // CommandEncryptService 依赖的 Redis mock
        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);
        encryptService = new CommandEncryptService(aesRedis);

        handler = new OtaProgressHandler(
                deviceMapper,
                recordMapper,
                taskMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                webSocketServer,
                logService
        );
    }

    @Test
    void testHandleOtaProgressSuccessWithRealEncryption() throws Exception {
        String deviceCode = "DEV001";

        // 升级记录准备
        IotOtaUpgradeRecord record = new IotOtaUpgradeRecord();
        record.setId("rec-1");
        record.setTaskId("task-1");
        record.setDeviceId("dev-id-1");
        record.setDeviceCode(deviceCode);
        record.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(recordMapper.selectOne(Mockito.any())).thenReturn(record);

        // 设备信息
        IotDevice device = new IotDevice();
        device.setId("dev-id-1");
        device.setDeviceCode(deviceCode);
        when(deviceMapper.update(Mockito.isNull(), Mockito.any())).thenReturn(1);

        // Redis value ops
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        // 1. 构造明文 OtaProgress（假设已下载完成并升级成功）
        MqttMessageModel.OtaProgress progress = MqttMessageModel.OtaProgress.builder()
                .taskId("task-1")
                .recordId(null) // 走按 deviceCode 查询最近一条的逻辑
                .status(DeviceConstant.OtaUpgradeStatus.SUCCESS)
                .progress(100)
                .downloadedBytes(1024L * 1024)
                .newVersion("1.0.1")
                .failReason(null)
                .timestamp(System.currentTimeMillis())
                .build();

        String plainJson = objectMapper.writeValueAsString(progress);

        // 2. 正式逻辑加密
        String encPayload = encryptService.encryptForDevice(deviceCode, plainJson);

        // 3. 调用处理器
        handler.handle(deviceCode, encPayload);

        // 4. 断点续传进度写入 Redis
        Mockito.verify(valueOps).set(
                Mockito.eq(DeviceConstant.RedisKey.OTA_PROGRESS_PREFIX + deviceCode + ":" + record.getId()),
                Mockito.eq(String.valueOf(progress.getDownloadedBytes())),
                Mockito.eq(DeviceConstant.Timeout.OTA_UPGRADE_TIMEOUT_MINUTES + 10L),
                Mockito.any()
        );

        // 5. 升级记录被 updateById
        ArgumentCaptor<IotOtaUpgradeRecord> updCaptor = ArgumentCaptor.forClass(IotOtaUpgradeRecord.class);
        Mockito.verify(recordMapper).updateById(updCaptor.capture());
        IotOtaUpgradeRecord upd = updCaptor.getValue();
        assertThat(upd.getId()).isEqualTo(record.getId());
        assertThat(upd.getUpgradeStatus()).isEqualTo(DeviceConstant.OtaUpgradeStatus.SUCCESS);
        assertThat(upd.getDownloadProgress()).isEqualTo(100);
        assertThat(upd.getDownloadedBytes()).isEqualTo(1024L * 1024);
        assertThat(upd.getFinishTime()).isNotNull();

        // 6. 任务统计刷新
        Mockito.verify(taskMapper).refreshTaskStatistics(record.getTaskId());

        // 7. WebSocket 推送
        Mockito.verify(webSocketServer).pushOtaProgress(deviceCode, progress.getTaskId(), progress.getStatus(), progress.getProgress());

        // 8. 终态成功时记录操作日志
        Mockito.verify(logService).recordLog(
                Mockito.eq(record.getDeviceId()),
                Mockito.eq(deviceCode),
                Mockito.eq(DeviceConstant.OperationType.FIRMWARE_UPGRADE),
                Mockito.contains("成功"),
                Mockito.isNull(),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull()
        );
    }
}

