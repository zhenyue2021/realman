package org.jeecg.modules.device.mqtt.handler;

import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.jeecg.modules.device.datacollect.handler.CollectUrlRequestHandler;
import org.jeecg.modules.device.datacollect.handler.OssAddressReportHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

/**
 * 基于 {@link MqttMessageDispatcher} 的单元测试，
 * 通过直接调用 dispatch 方法模拟设备上报的各种指令 Topic。
 *
 * 运行方式：
 * - 直接在 IDE 中运行本测试类；
 * - 或在命令行执行：mvn -pl realman-boot-iot/realman-boot-iot-biz test -Dtest=MqttMessageDispatcherTest
 *
 * 如需调整设备编码、Payload 等，只需要修改下面各个测试用例中的 topic / payload 字符串。
 */
public class MqttMessageDispatcherTest {

    private DeviceStatusHandler statusHandler;
    private DeviceConfigAckHandler configAckHandler;
    private DeviceCommandAckHandler commandAckHandler;
    private MasterCommandAckHandler masterCommandAckHandler;
    private OtaProgressHandler otaProgressHandler;
    private DeviceOperationLogHandler operationLogHandler;
    private DeviceOnlineOfflineHandler onlineOfflineHandler;
    private DeviceCameraStreamResponseHandler deviceCameraStreamResponseHandler;
    private MasterAssociatedDeviceResponseHandler masterAssociatedDeviceResponseHandler;
    private RobotSlaveStatusHandler robotSlaveStatusHandler;
    private SlamAckHandler slamAckHandler;
    private SlamStatesHandler slamStatesHandler;
    private ExtParamsRequestHandler extParamsRequestHandler;
    private MasterCommandHandler masterCommandHandler;
    private WebRtcAckHandler webRtcAckHandler;
    private WebRtcRestartHandler webRtcRestartHandler;
    private CollectUrlRequestHandler collectUrlRequestHandler;
    private OssAddressReportHandler ossAddressReportHandler;
    private DeviceOnlineReportHandler deviceOnlineReportHandler;

    private MqttMessageDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        statusHandler = Mockito.mock(DeviceStatusHandler.class);
        configAckHandler = Mockito.mock(DeviceConfigAckHandler.class);
        commandAckHandler = Mockito.mock(DeviceCommandAckHandler.class);
        masterCommandAckHandler = Mockito.mock(MasterCommandAckHandler.class);
        otaProgressHandler = Mockito.mock(OtaProgressHandler.class);
        operationLogHandler = Mockito.mock(DeviceOperationLogHandler.class);
        onlineOfflineHandler = Mockito.mock(DeviceOnlineOfflineHandler.class);
        deviceCameraStreamResponseHandler = Mockito.mock(DeviceCameraStreamResponseHandler.class);
        masterAssociatedDeviceResponseHandler = Mockito.mock(MasterAssociatedDeviceResponseHandler.class);
        robotSlaveStatusHandler = Mockito.mock(RobotSlaveStatusHandler.class);
        slamAckHandler = Mockito.mock(SlamAckHandler.class);
        slamStatesHandler = Mockito.mock(SlamStatesHandler.class);
        extParamsRequestHandler = Mockito.mock(ExtParamsRequestHandler.class);
        masterCommandHandler = Mockito.mock(MasterCommandHandler.class);
        webRtcAckHandler = Mockito.mock(WebRtcAckHandler.class);
        webRtcRestartHandler = Mockito.mock(WebRtcRestartHandler.class);
        collectUrlRequestHandler = Mockito.mock(CollectUrlRequestHandler.class);
        ossAddressReportHandler = Mockito.mock(OssAddressReportHandler.class);
        deviceOnlineReportHandler = Mockito.mock(DeviceOnlineReportHandler.class);

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
        // collectUrlRequestHandler / taskExecutor 均为非 final 字段（@Autowired 注入），需反射设值
        Field f = MqttMessageDispatcher.class.getDeclaredField("collectUrlRequestHandler");
        f.setAccessible(true);
        f.set(dispatcher, collectUrlRequestHandler);
        // 测试用同步 Executor：在调用线程立即执行，Mockito.verify() 无需等待
        Field fe = MqttMessageDispatcher.class.getDeclaredField("taskExecutor");
        fe.setAccessible(true);
        fe.set(dispatcher, (java.util.concurrent.Executor) Runnable::run);
    }

    private static MqttMessage mqttMsg(String payload) {
        return new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testSysOnlineEvent() {
        String topic = "$SYS/brokers/emqx@127.0.0.1/clients/DEV001/connected";
        String payload = "{\"clientid\":\"DEV001\",\"username\":\"dev001\",\"peerhost\":\"127.0.0.1\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(onlineOfflineHandler).handleOnline(topic, payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler
        );
    }

    @Test
    void testSysOfflineEvent() {
        String topic = "$SYS/brokers/emqx@127.0.0.1/clients/DEV001/disconnected";
        String payload = "{\"clientid\":\"DEV001\",\"reason\":\"timeout\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(onlineOfflineHandler).handleOffline(topic, payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler
        );
    }

    @Test
    void testStatusReport() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/status/report";
        String payload = "{\"ts\":1710000000000,\"status\":\"OK\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(statusHandler).handle(deviceCode, payload);
        Mockito.verifyNoMoreInteractions(
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testConfigAck() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/config/ack";
        String payload = "{\"configId\":\"CFG-001\",\"result\":\"SUCCESS\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(configAckHandler).handle(deviceCode, payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testRestartAck() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/command/restart/ack";
        String payload = "{\"commandId\":\"CMD-RESTART-001\",\"result\":\"SUCCESS\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(commandAckHandler).handle(deviceCode, "restart", payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testEmergencyStopAck() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/command/emergency-stop/ack";
        String payload = "{\"commandId\":\"CMD-STOP-001\",\"code\":0,\"message\":\"OK\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(commandAckHandler).handle(deviceCode, "emergency-stop", payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testOtaProgress() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/ota/progress";
        String payload = "{\"taskId\":\"OTA-001\",\"progress\":80,\"status\":\"DOWNLOADING\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(otaProgressHandler).handle(deviceCode, payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testOperationLog() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/log/operation";
        String payload = "{\"level\":\"INFO\",\"msg\":\"device started\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verify(operationLogHandler).handle(deviceCode, payload);
        Mockito.verifyNoMoreInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testSlamAck() throws Exception {
        String deviceCode = "ROBOT001";
        String topic = "device/" + deviceCode + "/slam/ack";
        String payload = "{\"requestId\":\"req1\",\"success\":true}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(slamAckHandler).handle(deviceCode, payload);
    }

    @Test
    void testSlamStates() throws Exception {
        String deviceCode = "ROBOT001";
        String topic = "device/" + deviceCode + "/slam/states";
        String payload = "{\"slamNavMode\":\"Mapping\"}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(slamStatesHandler).handle(deviceCode, payload);
    }

    @Test
    void testWebRtcRestart() throws Exception {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/webrtc/restart";
        String payload = "{\"command\":\"restart\",\"commandId\":\"97127fb9b78a4d00b217eeb42c0d5041\",\"timestamp\":1775716304420}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(webRtcRestartHandler).handle(deviceCode, payload);
    }

    @Test
    void testCollectUrlRequest() throws Exception {
        String deviceCode = "ROBOT001";
        String topic = "device/" + deviceCode + "/datacollect/collectUrlRequest";
        String payload = "{\"requestId\":\"req-001\",\"timestamp\":1710000000000}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(collectUrlRequestHandler).handle(deviceCode, payload);
    }

    @Test
    void testOssAddressReport() throws Exception {
        String deviceCode = "ROBOT001";
        String topic = "device/" + deviceCode + "/datacollect/ossAdressReport";
        String payload = "{\"timestamp\":1710000000000,\"oss\":{\"address\":\"oss://bucket/path\",\"list\":[\"file1.bag\"]}}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(ossAddressReportHandler).handle(deviceCode, payload);
    }

    @Test
    void testDeviceOnlineReport() throws Exception {
        String deviceCode = "ROBOT001";
        String topic = "device/" + deviceCode + "/datacollect/deviceOnline";
        String payload = "{\"timestamp\":1745393452000,\"deviceSn\":\"ROBOT001\"," +
                "\"payload\":{\"deviceType\":\"Realbot1.2\",\"version\":\"v1.0.1\"," +
                "\"location\":{\"latitude\":30.0,\"longitude\":120.0}}}";
        dispatcher.dispatch(topic, mqttMsg(payload));
        Mockito.verify(deviceOnlineReportHandler).handle(deviceCode, payload);
    }

    @Test
    void testUnknownBusinessTopic() {
        String deviceCode = "DEV001";
        String topic = "device/" + deviceCode + "/unknown/path";
        String payload = "{\"foo\":\"bar\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verifyNoInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }

    @Test
    void testNotMatchDevicePattern() {
        String topic = "invalid/topic";
        String payload = "{\"foo\":\"bar\"}";

        dispatcher.dispatch(topic, mqttMsg(payload));

        Mockito.verifyNoInteractions(
                statusHandler,
                configAckHandler,
                commandAckHandler,
                otaProgressHandler,
                operationLogHandler,
                onlineOfflineHandler
        );
    }
}
