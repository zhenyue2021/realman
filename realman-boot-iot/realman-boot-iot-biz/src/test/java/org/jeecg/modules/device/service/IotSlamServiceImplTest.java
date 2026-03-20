package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.entity.IotRobotSlamBinding;
import org.jeecg.modules.device.entity.IotSlamMap;
import org.jeecg.modules.device.entity.IotSlamSyncTask;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.mapper.IotRobotSlamBindingMapper;
import org.jeecg.modules.device.mapper.IotSlamMapMapper;
import org.jeecg.modules.device.mapper.IotSlamSyncTaskMapper;
import org.jeecg.modules.device.mqtt.MqttMessageModel;
import org.jeecg.modules.device.mqtt.publisher.MqttPublisher;
import org.jeecg.modules.device.service.impl.IotSlamServiceImpl;
import org.jeecg.modules.device.util.MinioUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class IotSlamServiceImplTest {

    private IotSlamMapMapper slamMapMapper;
    private IotRobotSlamBindingMapper bindingMapper;
    private IotSlamSyncTaskMapper taskMapper;
    private IotDeviceMapper deviceMapper;
    private MinioClient minioClient;
    private MinioUtil minioUtil;
    private MqttPublisher mqttPublisher;
    private StringRedisTemplate redisTemplate;

    private IotSlamServiceImpl service;

    @BeforeEach
    void setUp() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotDevice.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotRobotSlamBinding.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), ""), IotSlamSyncTask.class);

        slamMapMapper = Mockito.mock(IotSlamMapMapper.class);
        bindingMapper = Mockito.mock(IotRobotSlamBindingMapper.class);
        taskMapper = Mockito.mock(IotSlamSyncTaskMapper.class);
        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        minioClient = Mockito.mock(MinioClient.class);
        minioUtil = Mockito.mock(MinioUtil.class);
        mqttPublisher = Mockito.mock(MqttPublisher.class);
        redisTemplate = Mockito.mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service = new IotSlamServiceImpl(
                slamMapMapper, bindingMapper, taskMapper, deviceMapper,
                minioClient, mqttPublisher, redisTemplate, new ObjectMapper(), minioUtil);
        ReflectionTestUtils.setField(service, "bucketName", "iot-firmware");
    }

    @Test
    void handleUploadRequestShouldCreateUploadingMap() throws Exception {
        IotDevice robot = new IotDevice();
        robot.setId("r1");
        robot.setDeviceCode("RB001");
        robot.setDeviceType(1);
        robot.setTenantId(1);
        when(deviceMapper.selectOne(Mockito.<LambdaQueryWrapper<IotDevice>>any())).thenReturn(robot);
        when(minioClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class))).thenReturn("http://minio/put");

        MqttMessageModel.SlamUploadRequest req = MqttMessageModel.SlamUploadRequest.builder()
                .requestId("req-1")
                .mapName("厂区地图")
                .ext("zip")
                .md5("abc")
                .size(123L)
                .build();

        MqttMessageModel.SlamUploadPermit permit = service.handleUploadRequest("RB001", req);
        assertThat(permit.getRequestId()).isEqualTo("req-1");
        assertThat(permit.getPutUrl()).isEqualTo("http://minio/put");

        ArgumentCaptor<IotSlamMap> mapCaptor = ArgumentCaptor.forClass(IotSlamMap.class);
        Mockito.verify(slamMapMapper).insert(mapCaptor.capture());
        assertThat(mapCaptor.getValue().getStatus()).isEqualTo(DeviceConstant.SlamMapStatus.UPLOADING);
        assertThat(mapCaptor.getValue().getSourceRobotId()).isEqualTo("r1");
    }

    @Test
    void handleSyncAckSuccessShouldPromotePendingBinding() {
        IotRobotSlamBinding pending = new IotRobotSlamBinding();
        pending.setId("b1");
        pending.setRobotId("r1");
        pending.setPendingTaskId("t1");
        pending.setState(DeviceConstant.SlamBindingState.PENDING);
        when(bindingMapper.selectById("b1")).thenReturn(pending);

        IotSlamSyncTask task = new IotSlamSyncTask();
        task.setId("t1");
        when(taskMapper.selectById("t1")).thenReturn(task);
        when(bindingMapper.selectCount(any())).thenReturn(0L, 1L, 0L);

        MqttMessageModel.SlamSyncAck ack = MqttMessageModel.SlamSyncAck.builder()
                .bindingId("b1")
                .taskId("t1")
                .code(0)
                .build();
        service.handleSyncAck("RB001", ack);

        ArgumentCaptor<IotRobotSlamBinding> captor = ArgumentCaptor.forClass(IotRobotSlamBinding.class);
        Mockito.verify(bindingMapper).updateById(captor.capture());
        assertThat(captor.getValue().getState()).isEqualTo(DeviceConstant.SlamBindingState.ACTIVE);
        Mockito.verify(taskMapper).updateById(any(IotSlamSyncTask.class));
    }
}

