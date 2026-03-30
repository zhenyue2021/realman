package org.jeecg.modules.device.mqtt.handler;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.jeecg.modules.device.service.ForceFeedbackQueryPendingService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.websocket.DeviceWebSocketServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 从 MQTT Topic + 加密 Payload → 分发器 → 各 Handler 的整链路集成测试。
 *
 * 使用真实的 AES 加解密逻辑（CommandEncryptService），
 * 以及各 Handler 的正式实现，只对 DB/Redis/WebSocket/日志等依赖做 Mock。
 */
public class MqttMessageDispatcherEndToEndTest {

    private CommandEncryptService encryptService;
    private ObjectMapper objectMapper;

    // 依赖 Mock
    private IotDeviceMapper deviceMapper;
    private IotDeviceStatusMapper statusMapper;
    private IotDeviceConfigMapper configMapper;
    private IotOtaUpgradeRecordMapper recordMapper;
    private IotOtaUpgradeTaskMapper taskMapper;
    private StringRedisTemplate redisTemplate;
    private DeviceWebSocketServer webSocketServer;
    private IDeviceOperationLogService logService;
    private DeviceOnlineOfflineHandler onlineOfflineHandler;
    private ForceFeedbackQueryPendingService forceFeedbackPending;

    // 被测 Handler
    private DeviceStatusHandler statusHandler;
    private DeviceConfigAckHandler configAckHandler;
    private DeviceCommandAckHandler commandAckHandler;
    private MasterCommandAckHandler masterCommandAckHandler;
    private OtaProgressHandler otaProgressHandler;
    private DeviceOperationLogHandler operationLogHandler;
    private ExtParamsRequestHandler extParamsRequestHandler;

    // 分发器
    private MqttMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDevice.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDeviceConfig.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotOtaUpgradeRecord.class);
        objectMapper = new ObjectMapper();

        // CommandEncryptService 用单独的 Redis 缓存 AES Key
        StringRedisTemplate aesRedis = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> aesOps = Mockito.mock(ValueOperations.class);
        when(aesRedis.opsForValue()).thenReturn(aesOps);
        encryptService = new CommandEncryptService(aesRedis);

        // 公共依赖 Mock
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        statusMapper = Mockito.mock(IotDeviceStatusMapper.class);
        configMapper = Mockito.mock(IotDeviceConfigMapper.class);
        recordMapper = Mockito.mock(IotOtaUpgradeRecordMapper.class);
        taskMapper = Mockito.mock(IotOtaUpgradeTaskMapper.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        webSocketServer = Mockito.mock(DeviceWebSocketServer.class);
        logService = Mockito.mock(IDeviceOperationLogService.class);
        onlineOfflineHandler = Mockito.mock(DeviceOnlineOfflineHandler.class);

        // Redis ops for handlers
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        @SuppressWarnings("unchecked")
        SetOperations<String, String> setOps = Mockito.mock(SetOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(redisTemplate.opsForSet()).thenReturn(setOps);

        // 构造各 Handler 实例
        statusHandler = new DeviceStatusHandler(
                deviceMapper,
                statusMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                webSocketServer
        );

        configAckHandler = new DeviceConfigAckHandler(
                configMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                logService
        );

        commandAckHandler = new DeviceCommandAckHandler(
                encryptService,
                objectMapper,
                logService,
                configMapper,
                deviceMapper
        );
        masterCommandAckHandler = Mockito.mock(MasterCommandAckHandler.class);

        otaProgressHandler = new OtaProgressHandler(
                deviceMapper,
                recordMapper,
                taskMapper,
                encryptService,
                objectMapper,
                redisTemplate,
                webSocketServer,
                logService
        );

        operationLogHandler = new DeviceOperationLogHandler(
                encryptService,
                objectMapper,
                logService
        );

        DeviceCameraStreamPendingService cameraStreamPendingService = Mockito.mock(DeviceCameraStreamPendingService.class);
        DeviceCameraStreamResponseHandler deviceCameraStreamResponseHandler = new DeviceCameraStreamResponseHandler(
                encryptService,
                objectMapper,
                cameraStreamPendingService
        );

        MasterAssociatedDeviceResponseHandler masterAssociatedDeviceResponseHandler =
                Mockito.mock(MasterAssociatedDeviceResponseHandler.class);

        RobotSlaveStatusHandler robotSlaveStatusHandler = Mockito.mock(RobotSlaveStatusHandler.class);
        SlamUploadRequestHandler slamUploadRequestHandler = Mockito.mock(SlamUploadRequestHandler.class);
        SlamUploadCompleteHandler slamUploadCompleteHandler = Mockito.mock(SlamUploadCompleteHandler.class);
        SlamSyncAckHandler slamSyncAckHandler = Mockito.mock(SlamSyncAckHandler.class);

        // 分发器使用真实实例
        dispatcher = new MqttMessageDispatcher(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                masterCommandAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler,
                deviceCameraStreamResponseHandler,
                masterAssociatedDeviceResponseHandler,
                robotSlaveStatusHandler,
                slamUploadRequestHandler,
                slamUploadCompleteHandler,
                slamSyncAckHandler,
                extParamsRequestHandler
        );
    }

    private static MqttMessage mqtt(String body) {
        return new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 整链路：设备状态上报 → 分发器 → DeviceStatusHandler → DB/Redis/WebSocket
     */
    @Test
    void endToEnd_statusReport() throws Exception {
        String deviceCode = "DEV001";

        IotDevice device = new IotDevice();
        device.setId("dev-id-1");
        device.setDeviceCode(deviceCode);
        when(deviceMapper.selectOne(Mockito.any())).thenReturn(device);

        long now = System.currentTimeMillis();
        MqttMessageModel.StatusReport report = MqttMessageModel.StatusReport.builder()
                .temperature(new BigDecimal("26.0"))
                .humidity(new BigDecimal("55.0"))
                .batteryLevel(new BigDecimal("88"))
                .signalStrength(-65)
                .runStatus(1)
                .longitude(new BigDecimal("120.000001"))
                .latitude(new BigDecimal("30.000001"))
                .timestamp(now)
                .build();

        String plain = objectMapper.writeValueAsString(report);
        String enc = encryptService.encryptForDevice(deviceCode, plain);

        String topic = "device/" + deviceCode + "/status/report";
        dispatcher.dispatch(topic, mqtt(enc));

        // 设备状态更新
        ArgumentCaptor<IotDevice> devCap = ArgumentCaptor.forClass(IotDevice.class);
        Mockito.verify(deviceMapper).updateById(devCap.capture());
        IotDevice updated = devCap.getValue();
        assertThat(updated.getStatus()).isEqualTo(DeviceConstant.DeviceStatus.ONLINE);
        assertThat(updated.getLongitude()).isEqualByComparingTo(new BigDecimal("120.000001"));
        assertThat(updated.getLatitude()).isEqualByComparingTo(new BigDecimal("30.000001"));

        // 历史状态写入
        Mockito.verify(statusMapper).insert(Mockito.any(IotDeviceStatus.class));

        // WebSocket 推送
        Mockito.verify(webSocketServer).pushDeviceStatus(deviceCode, plain);
    }

    /**
     * 整链路：配置确认上报 → 分发器 → DeviceConfigAckHandler
     */
    @Test
    void endToEnd_configAck() throws Exception {
        String deviceCode = "DEV001";
        String commandId = "CFG-001";

        MqttMessageModel.ConfigAck ack = MqttMessageModel.ConfigAck.builder()
                .commandId(commandId)
                .code(0)
                .message("OK")
                .timestamp(System.currentTimeMillis())
                .build();

        String plain = objectMapper.writeValueAsString(ack);
        String enc = encryptService.encryptForDevice(deviceCode, plain);

        String topic = "device/" + deviceCode + "/config/ack";
        dispatcher.dispatch(topic, mqtt(enc));

        Mockito.verify(redisTemplate).delete(
                DeviceConstant.RedisKey.CONFIG_SYNC_PREFIX + deviceCode + ":" + commandId
        );
        Mockito.verify(configMapper).update(Mockito.isNull(), Mockito.any());
        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq(DeviceConstant.OperationType.PARAM_MODIFY),
                Mockito.contains("成功"),
                Mockito.anyString(),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.isNull()
        );
    }

    /**
     * 整链路：指令确认上报（restart） → 分发器 → DeviceCommandAckHandler
     */
    @Test
    void endToEnd_restartAck() throws Exception {
        String deviceCode = "DEV001";
        String commandId = "RST-001";

        MqttMessageModel.RestartAck ack = MqttMessageModel.RestartAck.builder()
                .commandId(commandId)
                .code(0)
                .message("will reboot")
                .timestamp(System.currentTimeMillis())
                .build();

        String plain = objectMapper.writeValueAsString(ack);
        String enc = encryptService.encryptForDevice(deviceCode, plain);

        String topic = "device/" + deviceCode + "/command/restart/ack";
        dispatcher.dispatch(topic, mqtt(enc));

        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq(DeviceConstant.OperationType.REMOTE_RESTART),
                Mockito.contains("执行"),
                Mockito.anyString(),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.anyString(),
                Mockito.isNull(),
                Mockito.isNull()
        );
    }

    /**
     * 整链路：OTA 进度上报（成功） → 分发器 → OtaProgressHandler
     */
    @Test
    void endToEnd_otaProgressSuccess() throws Exception {
        String deviceCode = "DEV001";

        IotOtaUpgradeRecord record = new IotOtaUpgradeRecord();
        record.setId("rec-1");
        record.setTaskId("task-1");
        record.setDeviceId("dev-id-1");
        record.setDeviceCode(deviceCode);
        record.setStartTime(LocalDateTime.now().minusMinutes(1));
        when(recordMapper.selectOne(Mockito.any())).thenReturn(record);

        MqttMessageModel.OtaProgress progress = MqttMessageModel.OtaProgress.builder()
                .taskId("task-1")
                .recordId(null)
                .status(DeviceConstant.OtaUpgradeStatus.SUCCESS)
                .progress(100)
                .downloadedBytes(2048L)
                .newVersion("1.0.1")
                .timestamp(System.currentTimeMillis())
                .build();

        String plain = objectMapper.writeValueAsString(progress);
        String enc = encryptService.encryptForDevice(deviceCode, plain);

        String topic = "device/" + deviceCode + "/ota/progress";
        dispatcher.dispatch(topic, mqtt(enc));

        Mockito.verify(recordMapper).updateById(Mockito.any(IotOtaUpgradeRecord.class));
        Mockito.verify(taskMapper).refreshTaskStatistics(record.getTaskId());
        Mockito.verify(webSocketServer).pushOtaProgress(deviceCode, progress.getTaskId(), progress.getStatus(), progress.getProgress());
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

    /**
     * 整链路：设备操作日志上报 → 分发器 → DeviceOperationLogHandler
     */
    @Test
    void endToEnd_operationLog() throws Exception {
        String deviceCode = "DEV001";
        long opTime = System.currentTimeMillis();

        MqttMessageModel.OperationLogReport report = MqttMessageModel.OperationLogReport.builder()
                .operationType("LOCAL_RESTART")
                .operationDesc("device rebooted locally")
                .operationDetail("{\"reason\":\"watchdog\"}")
                .operationResult("SUCCESS")
                .operationTime(opTime)
                .build();

        String plain = objectMapper.writeValueAsString(report);
        String enc = encryptService.encryptForDevice(deviceCode, plain);

        String topic = "device/" + deviceCode + "/log/operation";
        dispatcher.dispatch(topic, mqtt(enc));

        Mockito.verify(logService).recordLog(
                Mockito.isNull(),
                Mockito.eq(deviceCode),
                Mockito.eq("LOCAL_RESTART"),
                Mockito.eq("device rebooted locally"),
                Mockito.eq("{\"reason\":\"watchdog\"}"),
                Mockito.eq(DeviceConstant.OperationSource.DEVICE),
                Mockito.eq("SUCCESS"),
                Mockito.isNull(),
                Mockito.isNull(),
                Mockito.any()
        );
    }
}

