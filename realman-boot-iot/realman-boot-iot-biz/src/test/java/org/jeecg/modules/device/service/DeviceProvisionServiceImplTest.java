package org.jeecg.modules.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.jeecg.common.util.Md5Util;
import org.jeecg.modules.device.config.DeviceProvisionProperties;
import org.jeecg.modules.device.constant.DeviceConstant;
import org.jeecg.modules.device.constant.DeviceProvisionBizCode;
import org.jeecg.modules.device.dto.DeviceProvisionRequestDTO;
import org.jeecg.modules.device.dto.DeviceProvisionResponseDTO;
import org.jeecg.modules.device.entity.IotDevice;
import org.jeecg.modules.device.mapper.IotDeviceMapper;
import org.jeecg.modules.device.security.DeviceSecretService;
import org.jeecg.modules.device.service.impl.DeviceProvisionServiceImpl;
import org.jeecg.modules.device.service.impl.device.IotDeviceLifecycleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceProvisionServiceImplTest {

    private static final String DEVICE_CODE = "RM-2026000123";
    private static final String MAC = "aa-bb-cc-dd-ee-ff";

    private DeviceProvisionProperties properties;
    private IotDeviceMapper deviceMapper;
    private IotDeviceLifecycleService lifecycleService;
    private IDeviceOperationLogService logService;
    private DeviceSecretService secretService;
    private DeviceProvisionServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new DeviceProvisionProperties();
        properties.setEnabled(true);
        properties.setTimestampSkewSeconds(300);
        properties.setDefaultTenantId(1000);

        deviceMapper = Mockito.mock(IotDeviceMapper.class);
        lifecycleService = Mockito.mock(IotDeviceLifecycleService.class);
        logService = Mockito.mock(IDeviceOperationLogService.class);
        secretService = Mockito.mock(DeviceSecretService.class);
        when(secretService.generateSecret(DEVICE_CODE)).thenReturn("md5-secret");
        service = new DeviceProvisionServiceImpl(properties, deviceMapper, lifecycleService, logService, secretService);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "mqttBrokerUrl", "tcp://broker:1883");
    }

    @Test
    @DisplayName("签名校验通过时创建新设备")
    void provisionCreatesNewDevice() {
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);
        IotDevice saved = new IotDevice();
        saved.setId("id-1");
        saved.setDeviceCode(DEVICE_CODE);
        saved.setDeviceSecret("md5-secret");
        saved.setStatus(DeviceConstant.DeviceStatus.INACTIVE);
        when(lifecycleService.addDevice(any(IotDevice.class))).thenReturn(saved);

        DeviceProvisionResponseDTO response = service.provision(buildValidRequest());

        assertThat(response.getBizCode()).isEqualTo(DeviceProvisionBizCode.REGISTERED_NEW.getCode());
        assertThat(response.getBizMessage()).isEqualTo("设备注册成功");
        assertThat(response.getDeviceCode()).isEqualTo(DEVICE_CODE);
        assertThat(response.getMqttPassword()).isEqualTo("md5-secret");
        assertThat(response.isNewlyRegistered()).isTrue();

        ArgumentCaptor<IotDevice> captor = ArgumentCaptor.forClass(IotDevice.class);
        verify(lifecycleService).addDevice(captor.capture());
        IotDevice toSave = captor.getValue();
        assertThat(toSave.getDeviceCode()).isEqualTo(DEVICE_CODE);
        assertThat(toSave.getSerialNumber()).isNull();
        assertThat(toSave.getMacAddress()).isEqualTo(MAC);
        assertThat(toSave.getDeviceModel()).isEqualTo("RM-X1");
        assertThat(toSave.getDeviceType()).isEqualTo(DeviceConstant.DeviceTypeInteger.ROBOT);
        assertThat(toSave.getDeviceSecret()).isEqualTo("md5-secret");
        assertThat(toSave.getTenantId()).isEqualTo(1000);
        verify(secretService).generateSecret(DEVICE_CODE);
    }

    @Test
    @DisplayName("设备已存在时幂等返回，不重复插入")
    void provisionReturnsExistingDevice() {
        IotDevice existing = new IotDevice();
        existing.setDeviceCode(DEVICE_CODE);
        existing.setDeviceSecret("existing-secret");
        existing.setStatus(DeviceConstant.DeviceStatus.OFFLINE);
        when(deviceMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(existing);

        DeviceProvisionResponseDTO response = service.provision(buildValidRequest());

        assertThat(response.getBizCode()).isEqualTo(DeviceProvisionBizCode.ALREADY_REGISTERED.getCode());
        assertThat(response.getBizMessage()).isEqualTo("设备已注册");
        assertThat(response.isNewlyRegistered()).isFalse();
        verify(lifecycleService, never()).addDevice(any());
    }

    @Test
    @DisplayName("签名错误时拒绝注册")
    void provisionRejectsInvalidSign() {
        DeviceProvisionRequestDTO request = buildValidRequest();
        request.setSign("bad-sign");

        DeviceProvisionResponseDTO response = service.provision(request);

        assertThat(response.getBizCode()).isEqualTo(DeviceProvisionBizCode.SIGN_INVALID.getCode());
        assertThat(response.getBizMessage()).isEqualTo("签名校验失败");
    }

    @Test
    @DisplayName("功能未启用时拒绝")
    void provisionRejectsWhenDisabled() {
        properties.setEnabled(false);

        DeviceProvisionResponseDTO response = service.provision(buildValidRequest());

        assertThat(response.getBizCode()).isEqualTo(DeviceProvisionBizCode.PROVISION_DISABLED.getCode());
        assertThat(response.getBizMessage()).contains("未启用");
    }

    private DeviceProvisionRequestDTO buildValidRequest() {
        long timestamp = Instant.now().toEpochMilli();
        String payload = DEVICE_CODE + "|" + MAC + "|" + timestamp;
        String sign = Md5Util.md5Encode(payload, "UTF_8").toUpperCase(Locale.ROOT);

        DeviceProvisionRequestDTO request = new DeviceProvisionRequestDTO();
        request.setDeviceCode(DEVICE_CODE);
        request.setMacAddress(MAC);
        request.setDeviceModel("RM-X1");
        request.setDeviceType(DeviceConstant.DeviceType.ROBOT);
        request.setTimestamp(timestamp);
        request.setSign(sign);
        return request;
    }
}
