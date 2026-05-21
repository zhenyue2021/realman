package org.jeecg.modules.device.mqtt.handler;

import java.lang.reflect.Field;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.jeecg.common.util.RedisUtil;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotDeviceConfig;
import org.jeecg.modules.device.entity.IotDeviceStatus;
import org.jeecg.modules.device.entity.IotOtaUpgradeRecord;
import org.jeecg.modules.device.mapper.*;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.security.CommandEncryptService;
import org.jeecg.modules.device.datacollect.handler.CollectUrlRequestHandler;
import org.jeecg.modules.device.datacollect.handler.OssAddressReportHandler;
import org.jeecg.modules.device.service.DeviceCameraStreamPendingService;
import org.jeecg.modules.device.service.ForceFeedbackQueryPendingService;
import org.jeecg.modules.device.service.IDeviceOperationLogService;
import org.jeecg.modules.device.service.IIotDeviceCommandRecordService;
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
    private IIotDeviceCommandRecordService commandRecordService;
    private DeviceOnlineOfflineHandler onlineOfflineHandler;
    private ForceFeedbackQueryPendingService forceFeedbackPending;

    private DeviceConfigAckHandler configAckHandler;
    private DeviceCommandAckHandler commandAckHandler;
    private MasterCommandAckHandler masterCommandAckHandler;
    private OtaProgressHandler otaProgressHandler;
    private DeviceOperationLogHandler operationLogHandler;
    private ExtParamsRequestHandler extParamsRequestHandler;
    private MasterCommandHandler masterCommandHandler;


    private SlamAckHandler slamAckHandler;
    private SlamStatesHandler slamStatesHandler;

    private MqttPublisher mqttPublisher;
    private RedisUtil redisUtil;
    private ExtParamRecordIotMapper extParamRecordIotMapper;
    private WebRtcAckHandler webRtcAckHandler;
    private WebRtcRestartHandler webRtcRestartHandler;
    private CollectUrlRequestHandler collectUrlRequestHandler;
    private OssAddressReportHandler ossAddressReportHandler;
    private DeviceOnlineReportHandler deviceOnlineReportHandler;

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
        commandRecordService = Mockito.mock(IIotDeviceCommandRecordService.class);
        onlineOfflineHandler = Mockito.mock(DeviceOnlineOfflineHandler.class);
        extParamsRequestHandler = Mockito.mock(ExtParamsRequestHandler.class);
        masterCommandHandler = Mockito.mock(MasterCommandHandler.class);

        // pipeline stub（touchPresence 走 executePipelined，不再用 opsForValue/opsForSet）
        when(redisTemplate.executePipelined(
                Mockito.<org.springframework.data.redis.core.RedisCallback<Object>>any()))
                .thenReturn(java.util.Collections.emptyList());

        // 构造各 Handler 实例
        // DeviceStatusHandler 仅维护 Redis 在线态
        DeviceStatusHandler statusHandler = new DeviceStatusHandler(redisTemplate);

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
                commandRecordService,
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

        extParamsRequestHandler = Mockito.mock(ExtParamsRequestHandler.class);
        masterCommandHandler = Mockito.mock(MasterCommandHandler.class);
        slamAckHandler = Mockito.mock(SlamAckHandler.class);
        slamStatesHandler = Mockito.mock(SlamStatesHandler.class);
        webRtcAckHandler = Mockito.mock(WebRtcAckHandler.class);
        webRtcRestartHandler = new WebRtcRestartHandler(encryptService, objectMapper, webSocketServer, logService);
        collectUrlRequestHandler = Mockito.mock(CollectUrlRequestHandler.class);
        ossAddressReportHandler = Mockito.mock(OssAddressReportHandler.class);
        deviceOnlineReportHandler = Mockito.mock(DeviceOnlineReportHandler.class);
        DeviceCameraStreamPendingService cameraStreamPendingService = Mockito.mock(DeviceCameraStreamPendingService.class);
        DeviceCameraStreamResponseHandler deviceCameraStreamResponseHandler = new DeviceCameraStreamResponseHandler(
                encryptService,
                objectMapper,
                cameraStreamPendingService
        );

        MasterAssociatedDeviceResponseHandler masterAssociatedDeviceResponseHandler =
                Mockito.mock(MasterAssociatedDeviceResponseHandler.class);

        RobotSlaveStatusHandler robotSlaveStatusHandler = Mockito.mock(RobotSlaveStatusHandler.class);

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
                slamAckHandler,
                slamStatesHandler,
                extParamsRequestHandler,
                masterCommandHandler,
                webRtcAckHandler,
                webRtcRestartHandler,
                ossAddressReportHandler,
                deviceOnlineReportHandler
        );
        // taskExecutor 为非 final 字段（@Autowired 注入），反射注入同步 Executor 保证断言可立即执行
        java.lang.reflect.Field fe = MqttMessageDispatcher.class.getDeclaredField("taskExecutor");
        fe.setAccessible(true);
        fe.set(dispatcher, (java.util.concurrent.Executor) Runnable::run);
        // collectUrlRequestHandler 为 @Autowired(required=false) 非 final 字段，不在构造器中，需反射注入
        Field collectUrlField = MqttMessageDispatcher.class.getDeclaredField("collectUrlRequestHandler");
        collectUrlField.setAccessible(true);
        collectUrlField.set(dispatcher, collectUrlRequestHandler);
    }

    private static MqttMessage mqtt(String body) {
        return new MqttMessage(body.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 整链路：设备 status/report → 分发器 → DeviceStatusHandler → Redis 在线态
     */
    @Test
    void endToEnd_statusReport() throws Exception {
        String deviceCode = "DEV001";

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

        Thread.sleep(200);
        Mockito.verify(redisTemplate).executePipelined(
                Mockito.<org.springframework.data.redis.core.RedisCallback<Object>>any());
        Mockito.verify(deviceMapper, Mockito.never()).updateById(Mockito.any(IotDevice.class));
        Mockito.verify(statusMapper, Mockito.never()).insert(Mockito.any(IotDeviceStatus.class));
        Mockito.verify(webSocketServer, Mockito.never()).pushDeviceStatus(Mockito.anyString(), Mockito.anyString());
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
        // 双向通信闭环：ACK 到达后 command record 应被更新为 SUCCESS
        Mockito.verify(commandRecordService).ack(
                Mockito.eq(commandId),
                Mockito.eq(true),
                Mockito.anyString(),
                Mockito.anyString()
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

